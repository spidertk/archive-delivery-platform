import json
import re
import tarfile
from pathlib import Path, PurePosixPath

TIME_OF_DAY_RE = re.compile(r"(\d{2}):(\d{2}):(\d{2})(\.\d+)?")
REQUEST_ID_RE = re.compile(r"^\d{14}[A-Za-z0-9]{25}$")


def normalize_request_time(value, archive_date):
    """生产协议使用交付日期和源时间部分，统一为 UTC 纳秒 ISO 8601。"""
    match = TIME_OF_DAY_RE.search(str(value or ""))
    if not match:
        return f"{archive_date}T00:00:00.000000000Z"
    fraction = ((match.group(4) or ".")[1:]).ljust(9, "0")[:9]
    return f"{archive_date}T{match.group(1)}:{match.group(2)}:{match.group(3)}.{fraction}Z"


def fix_request(request_dir, fallback_number, archive_date, provider_code):
    """合法 39 位源 requestId 保留；缺失或旧格式使用 Java 幂等号段确定性补号。"""
    request_dir = Path(request_dir)
    metadata_path = request_dir / "metadata.json"
    if not metadata_path.is_file():
        raise FileNotFoundError("Missing metadata.json in " + request_dir.name)
    if not (request_dir / "biz_request.json").is_file():
        raise FileNotFoundError("Missing biz_request.json in " + request_dir.name)
    with metadata_path.open(encoding="utf-8") as stream:
        metadata = json.load(stream)
    original_request_id = metadata.get("requestId")
    if original_request_id and REQUEST_ID_RE.fullmatch(str(original_request_id)):
        new_request_id = str(original_request_id)
    else:
        match = TIME_OF_DAY_RE.search(str(metadata.get("requestTime") or ""))
        time_part = "".join(match.group(1, 2, 3)) if match else "000000"
        new_request_id = archive_date.replace("-", "") + time_part + f"{fallback_number:025d}"
        if fallback_number < 1 or fallback_number > 9999999999999999999999999:
            raise ValueError("Fallback request number exceeds 25 digits")
    if not REQUEST_ID_RE.fullmatch(new_request_id):
        raise ValueError("requestId does not match the 39-character production protocol: " + new_request_id)
    metadata["requestId"] = new_request_id
    metadata["providerCode"] = provider_code
    metadata["requestTime"] = normalize_request_time(metadata.get("requestTime"), archive_date)
    stream_response = request_dir / "biz_stream_response.jsonl"
    sync_response = request_dir / "biz_response.json"
    mode = metadata.get("requestMode", "")
    if mode is None or mode == "":
        with (request_dir / "biz_request.json").open(encoding="utf-8") as stream:
            requested_stream = json.load(stream).get("stream")
        if isinstance(requested_stream, bool):
            mode = "STREAM" if requested_stream else "SYNC"
        elif stream_response.is_file() and not sync_response.is_file():
            mode = "STREAM"
        elif sync_response.is_file() and not stream_response.is_file():
            mode = "SYNC"
        metadata["requestMode"] = mode
    if mode == "STREAM" and sync_response.exists() and not stream_response.exists():
        sync_response.rename(stream_response)
    elif mode == "SYNC" and stream_response.exists() and not sync_response.exists():
        stream_response.rename(sync_response)
    if mode == "STREAM" and not stream_response.is_file():
        raise FileNotFoundError("Missing STREAM response in " + request_dir.name)
    if mode == "SYNC" and not sync_response.is_file():
        raise FileNotFoundError("Missing SYNC response in " + request_dir.name)
    if mode not in ("STREAM", "SYNC"):
        raise ValueError("Unsupported requestMode=" + repr(mode) + " in " + request_dir.name)
    with metadata_path.open("w", encoding="utf-8") as stream:
        json.dump(metadata, stream, ensure_ascii=False)
    return original_request_id, new_request_id


def verify_output(artifact, archive_date, provider_code):
    """一次顺序读取 gzip 即完成全部请求协议校验，避免逐请求回卷重解压。"""
    errors = []
    requests = {}
    seen = set()
    with tarfile.open(artifact, "r|gz") as archive:
        # metadata 在成员经过时读取；只保存协议字段和文件名，不保留响应正文。
        for member in archive:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or "\\" in member.name or member.issym() or member.islnk():
                raise ValueError("Protocol validation failed: unsafe archive member")
            if member.name in seen:
                raise ValueError("Protocol validation failed: duplicate archive member")
            seen.add(member.name)
            if not path.parts:
                continue
            request = requests.setdefault(path.parts[0], {"files": set(), "metadata": None})
            if len(path.parts) == 2 and member.isfile():
                request["files"].add(path.name)
                if path.name == "metadata.json":
                    with archive.extractfile(member) as stream:
                        request["metadata"] = json.load(stream)
    expected_time = re.compile(rf"^{re.escape(archive_date)}T\d{{2}}:\d{{2}}:\d{{2}}\.\d{{9}}Z$")
    if not requests:
        errors.append("archive contains no request directories")
    for directory, request in sorted(requests.items()):
        errors.extend(validate_request_metadata(directory, request, expected_time, provider_code))
    if errors:
        raise ValueError("Protocol validation failed: " + "; ".join(errors))


def validate_request_metadata(directory, request, expected_time, provider_code):
    """索引完成后校验直属协议文件，保持 ID、日期、实体和响应模式的失败边界。"""
    errors = []
    files, metadata = request["files"], request["metadata"]
    if not REQUEST_ID_RE.fullmatch(directory):
        return [directory + ": directory is not a 39-character requestId"]
    if not isinstance(metadata, dict):
        return [directory + ": missing or invalid metadata.json"]
    if "biz_request.json" not in files:
        errors.append(directory + ": missing biz_request.json")
    if metadata.get("requestId") != directory:
        errors.append(directory + ": requestId does not match directory")
    if not expected_time.fullmatch(str(metadata.get("requestTime") or "")):
        errors.append(directory + ": requestTime does not match ISO UTC nanoseconds")
    if metadata.get("providerCode") != provider_code:
        errors.append(directory + ": providerCode mismatch")
    mode = metadata.get("requestMode")
    if mode not in ("STREAM", "SYNC"):
        errors.append(directory + ": unsupported requestMode")
    if mode == "STREAM" and "biz_stream_response.jsonl" not in files:
        errors.append(directory + ": STREAM response mismatch")
    if mode == "SYNC" and "biz_response.json" not in files:
        errors.append(directory + ": SYNC response mismatch")
    return errors
