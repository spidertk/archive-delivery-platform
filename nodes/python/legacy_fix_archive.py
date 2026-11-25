import json
import re
import tarfile
from datetime import datetime
from pathlib import Path, PurePosixPath

REQUEST_TIME_RE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$")


def normalize_request_time(value):
    """保留源日期和时分秒，仅去除小数秒及格式后缀；缺失或无效时间不伪造业务事实。"""
    if not isinstance(value, str) or not value.strip():
        raise ValueError("Source requestTime is missing")
    match = re.fullmatch(r"(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?", value.strip())
    if not match:
        raise ValueError("Source requestTime is invalid")
    # 校验源日历和时钟值后只输出秒级原始墙上时间，不按交付日期改写或跨时区平移。
    formatted = f"{match.group(1)} {match.group(2)}"
    try:
        datetime.strptime(formatted, "%Y-%m-%d %H:%M:%S")
    except ValueError as error:
        raise ValueError("Source requestTime is invalid") from error
    return formatted


def source_request_id(metadata):
    """允许 UUID 等真实来源 ID；缺失和互相矛盾的字段必须失败，不再生成或改写编号。"""
    values = [metadata[key] for key in ("requestId", "request_id") if key in metadata]
    if not values or any(not isinstance(value, str) or not value.strip() for value in values):
        raise ValueError("Source requestId is missing or invalid")
    if len(set(values)) != 1:
        raise ValueError("Source requestId and request_id disagree")
    return values[0]


def fix_request(request_dir, provider_code):
    """保留原始请求 ID 和目录，只规范实体、时间格式与响应模式。"""
    request_dir = Path(request_dir)
    metadata_path = request_dir / "metadata.json"
    if not metadata_path.is_file():
        raise FileNotFoundError("Missing metadata.json in " + request_dir.name)
    if not (request_dir / "biz_request.json").is_file():
        raise FileNotFoundError("Missing biz_request.json in " + request_dir.name)
    with metadata_path.open(encoding="utf-8") as stream:
        metadata = json.load(stream)
    request_id = source_request_id(metadata)
    # requestId/request_id 是来源事实，保留字段名和值；目录与 ID 分别校验，不能强制互相改名。
    metadata["providerCode"] = provider_code
    metadata["requestTime"] = normalize_request_time(metadata.get("requestTime"))
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
    return request_id


def verify_output(artifact, provider_code):
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
    if not requests:
        errors.append("archive contains no request directories")
    for directory, request in sorted(requests.items()):
        errors.extend(validate_request_metadata(directory, request, provider_code))
    if errors:
        raise ValueError("Protocol validation failed: " + "; ".join(errors))


def validate_request_metadata(directory, request, provider_code):
    """校验真实源 ID 与秒级时间，不要求目录等于 ID，也不把来源日期改成交付日期。"""
    errors = []
    files, metadata = request["files"], request["metadata"]
    if not isinstance(metadata, dict):
        return [directory + ": missing or invalid metadata.json"]
    if "biz_request.json" not in files:
        errors.append(directory + ": missing biz_request.json")
    try:
        source_request_id(metadata)
        value = metadata.get("requestTime")
        if not REQUEST_TIME_RE.fullmatch(str(value or "")) or normalize_request_time(value) != value:
            raise ValueError("requestTime must use yyyy-MM-dd HH:mm:ss")
    except ValueError as error:
        errors.append(directory + ": " + str(error))
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
