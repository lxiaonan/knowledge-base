"""Smoke-test an isolated deployment. Creates a synthetic test knowledge base and document."""
import argparse
import http.cookiejar
import json
from pathlib import Path
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8088")
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env.deploy")
    parser.add_argument("--check-persisted", action="store_true")
    args = parser.parse_args()
    values = {}
    for line in args.env_file.read_text(encoding="utf-8-sig").splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value.strip().strip('"').strip("'")
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = {}

    def request(path, method="GET", data=None, content_type="application/json", raw=False):
        headers = {}
        if method != "GET":
            headers[csrf["headerName"]] = csrf["token"]
        if data is not None:
            headers["Content-Type"] = content_type
            if not isinstance(data, bytes):
                data = json.dumps(data, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(args.url.rstrip("/") + path, data=data, headers=headers, method=method)
        with opener.open(req, timeout=180) as response:
            body = response.read()
        return body if raw else json.loads(body) if body else None

    assert b'<div id="app">' in request("/", raw=True), "Frontend did not return the Vue application"
    system = request("/api/system")
    assert not system["demo"] and system["embeddingModel"] == values["AI_EMBEDDING_MODEL"]
    csrf.update(request("/api/auth/csrf"))
    user = request("/api/auth/login", "POST", {"username": "admin", "password": values["BOOTSTRAP_ADMIN_PASSWORD"]})
    assert user["role"] == "ADMIN"
    csrf.update(request("/api/auth/csrf"))
    print("Frontend, API proxy and administrator login verified.", flush=True)

    state = ROOT / ".deploy" / "smoke-state.json"
    if args.check_persisted:
        saved = json.loads(state.read_text(encoding="utf-8"))
        assert request(f'/api/documents/{saved["document"]}/file', raw=True).decode("utf-8") == saved["content"]
        settings = request("/api/settings/chat")
        assert settings["model"] == saved["model"]
        results = request(f'/api/knowledge-bases/{saved["base"]}/search', "POST", {"question": saved["question"]})
        assert results and results[0]["documentId"] == saved["document"]
        print("Uploaded file, encrypted settings and real vector search survived container restart.", flush=True)
        return

    base = request("/api/knowledge-bases", "POST", {"name": "部署验收-" + uuid.uuid4().hex[:8], "description": "Synthetic deployment smoke test"})
    content = "知序部署验收规范：每次发布前必须完成自动化测试，并由两位同事审核代码。文档上传后由本地 BGE-M3 生成向量，存入 PostgreSQL。"
    boundary = "zhixu" + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="deployment-smoke.txt"\r\n'
            f'Content-Type: text/plain; charset=utf-8\r\n\r\n{content}\r\n--{boundary}--\r\n').encode("utf-8")
    document = request(f'/api/knowledge-bases/{base["id"]}/documents', "POST", body, f"multipart/form-data; boundary={boundary}")
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        current = next(d for d in request(f'/api/knowledge-bases/{base["id"]}/documents') if d["id"] == document["id"])
        if current["status"] == "READY":
            break
        assert current["status"] != "FAILED", "Document indexing failed"
        time.sleep(2)
    else:
        raise AssertionError("Document indexing exceeded five minutes")
    question = "发布之前需要几位同事审核代码？"
    results = request(f'/api/knowledge-bases/{base["id"]}/search', "POST", {"question": question})
    assert results and results[0]["documentId"] == document["id"], "Real semantic search did not retrieve the uploaded file"
    assert request(f'/api/documents/{document["id"]}/file', raw=True).decode("utf-8") == content
    settings = request("/api/settings/chat")
    # Save existing settings without adding a chat credential or calling an external model.
    request("/api/settings/chat", "PUT", {"baseUrl": settings["baseUrl"], "model": settings["model"], "apiKey": ""})
    state.parent.mkdir(exist_ok=True)
    state.write_text(json.dumps({"base": base["id"], "document": document["id"], "content": content,
                                 "question": question, "model": settings["model"]}, ensure_ascii=False), encoding="utf-8")
    print("Document upload, local BGE-M3 indexing, semantic retrieval, download and encrypted settings verified.", flush=True)


if __name__ == "__main__":
    main()
