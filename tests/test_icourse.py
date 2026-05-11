import ddl_checker as dc


class _JsonResponse:
    status_code = 200
    headers = {"Content-Type": "application/json;charset=UTF-8"}

    def __init__(self, payload):
        self._payload = payload

    def json(self):
        return self._payload


class _WarmupSession:
    def __init__(self):
        self.cookies = {"NTESSTUDYSI": "csrf-token"}
        self.post_calls = 0
        self.get_calls = []

    def post(self, url, data, timeout):
        self.post_calls += 1
        assert url == "https://www.icourse163.org/web/j/courseBean.getLastLearnedMocTermDto.rpc"
        assert data == {"csrfKey": "csrf-token", "termId": "1476751568"}
        assert timeout == 15
        if self.post_calls == 1:
            return _JsonResponse({"code": 0, "result": None, "message": ""})
        return _JsonResponse({"code": 0, "result": {"mocTermDto": {"chapters": []}}})

    def get(self, url, timeout):
        self.get_calls.append((url, timeout))
        return _JsonResponse({"ok": True})


def test_icourse_rpc_warms_homepage_and_retries_empty_result():
    session = _WarmupSession()

    result = dc._icourse_rpc(session, 1476751568)

    assert result == {"mocTermDto": {"chapters": []}}
    assert session.post_calls == 2
    assert session.get_calls == [("https://www.icourse163.org/", 15)]


def test_extract_icourse_term_id_from_course_page_html():
    html = 'window.termDto = { termId : "1476751568", courseId : "1449794172" };'

    assert dc._extract_icourse_term_id("https://www.icourse163.org/course/SJTU-1449794172", html) == 1476751568
