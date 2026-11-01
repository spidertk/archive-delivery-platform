import json
import tarfile
from pathlib import Path


def fix_request(request_dir, new_request_id, archive_date, provider_code):
    request_dir = Path(request_dir)
    metadata_path = request_dir / "metadata.json"
    if not metadata_path.is_file():
        raise FileNotFoundError("Missing metadata.json in " + request_dir.name)
    if not (request_dir / "biz_request.json").is_file():
        raise FileNotFoundError("Missing biz_request.json in " + request_dir.name)
    with metadata_path.open(encoding="utf-8") as stream:
        metadata = json.load(stream)
    original_request_id = metadata.get("requestId")
    original_time = metadata.get("requestTime", "")
    time_part = original_time.split(" ", 1)[1] if " " in original_time else "00:00:00"
    metadata["requestId"] = new_request_id
    metadata["providerCode"] = provider_code
    metadata["requestTime"] = archive_date + " " + time_part
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
    with metadata_path.open("w", encoding="utf-8") as stream:
        json.dump(metadata, stream, ensure_ascii=False)
    return original_request_id


def verify_output(artifact, archive_date, provider_code):
    errors = []
    with tarfile.open(artifact, "r:gz") as archive:
        members = archive.getmembers()
        directories = sorted({Path(member.name).parts[0] for member in members
                              if len(Path(member.name).parts) > 1})
        for directory in directories:
            files = {Path(member.name).name for member in members
                     if member.isfile() and Path(member.name).parts[0] == directory}
            if "metadata.json" not in files:
                errors.append(directory + ": missing metadata.json")
                continue
            if "biz_request.json" not in files:
                errors.append(directory + ": missing biz_request.json")
            metadata = json.load(archive.extractfile(directory + "/metadata.json"))
            if metadata.get("requestId") != directory:
                errors.append(directory + ": requestId does not match directory")
            if not metadata.get("requestTime", "").startswith(archive_date + " "):
                errors.append(directory + ": requestTime does not match archive date")
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
