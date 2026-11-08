import json
import re
import tarfile
from pathlib import Path

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
    if mode == "STREAM" and sync_response.exists() and not stream_response.exists():
        sync_response.rename(stream_response)
    elif mode == "SYNC" and stream_response.exists() and not sync_response.exists():
        stream_response.rename(sync_response)
    if mode == "STREAM" and not stream_response.is_file():
        raise FileNotFoundError("Missing STREAM response in " + request_dir.name)
    if mode == "SYNC" and not sync_response.is_file():
        raise FileNotFoundError("Missing SYNC response in " + request_dir.name)
    if mode not in ("STREAM", "SYNC"):
        raise ValueError("Unsupported requestMode in " + request_dir.name)
    with metadata_path.open("w", encoding="utf-8") as stream:
        json.dump(metadata, stream, ensure_ascii=False)
    return original_request_id, new_request_id


def verify_output(artifact, archive_date, provider_code):
    errors = []
    with tarfile.open(artifact, "r:gz") as archive:
        members = archive.getmembers()
        directories = sorted({Path(member.name).parts[0] for member in members
                              if len(Path(member.name).parts) > 1})
        for directory in directories:
            files = {Path(member.name).name for member in members
                     if member.isfile() and Path(member.name).parts[0] == directory}
            if not REQUEST_ID_RE.fullmatch(directory):
                errors.append(directory + ": directory is not a 39-character requestId")
                continue
            if "metadata.json" not in files:
                errors.append(directory + ": missing metadata.json")
                continue
            if "biz_request.json" not in files:
                errors.append(directory + ": missing biz_request.json")
            metadata = json.load(archive.extractfile(directory + "/metadata.json"))
            if metadata.get("requestId") != directory:
                errors.append(directory + ": requestId does not match directory")
            expected_time = re.compile(rf"^{re.escape(archive_date)}T\d{{2}}:\d{{2}}:\d{{2}}\.\d{{9}}Z$")
            if not expected_time.fullmatch(str(metadata.get("requestTime") or "")):
                errors.append(directory + ": requestTime does not match ISO UTC nanoseconds")
            if metadata.get("providerCode") != provider_code:
                errors.append(directory + ": providerCode mismatch")
            has_stream = "biz_stream_response.jsonl" in files
            has_sync = "biz_response.json" in files
            if not has_stream and not has_sync:
                errors.append(directory + ": missing response")
            if metadata.get("requestMode") == "STREAM" and not has_stream:
                errors.append(directory + ": STREAM response mismatch")
            if metadata.get("requestMode") == "SYNC" and not has_sync:
                errors.append(directory + ": SYNC response mismatch")
    if errors:
        raise ValueError("Protocol validation failed: " + "; ".join(errors))
