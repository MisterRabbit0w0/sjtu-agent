from unittest.mock import patch

import ddl_checker as dc


class _JsonResponse:
    status_code = 200

    def __init__(self, payload, links=None):
        self._payload = payload
        self.links = links or {}

    def json(self):
        return self._payload

    def raise_for_status(self):
        return None


class _CanvasSession:
    def __init__(self):
        self.headers = {}

    def get(self, url, params=None, timeout=15):
        if url.endswith("/api/v1/courses"):
            return _JsonResponse([{"id": 123, "name": "测试课程"}])

        if url.endswith("/api/v1/courses/123/assignments"):
            params = params or {}
            if params.get("bucket") == "upcoming":
                return _JsonResponse([])
            if params.get("bucket") == "past":
                return _JsonResponse([])
            return _JsonResponse([
                {
                    "id": 456,
                    "name": "完整列表里的未来作业",
                    "due_at": "2030-01-01T23:59:00+08:00",
                    "submission_types": ["online_upload"],
                }
            ])

        if url.endswith("/api/v1/courses/123/students/submissions"):
            return _JsonResponse([])

        raise AssertionError(f"unexpected URL: {url}")


def test_canvas_fetch_uses_full_assignment_list_not_upcoming_bucket_only():
    with patch("ddl_checker.requests.Session", return_value=_CanvasSession()):
        items = dc.fetch_canvas({
            "canvas_token": "token",
            "canvas_base_url": "https://canvas.test",
        })

    assert [item["name"] for item in items] == ["完整列表里的未来作业"]
