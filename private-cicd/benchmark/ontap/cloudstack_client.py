#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
"""
Minimal CloudStack HTTP/REST API client used by the ONTAP plugin benchmark
scripts (private-cicd/benchmark/ontap/).

Only session-key based auth (login -> sessionkey + JSESSIONID cookie) is
implemented, matching the approach documented in:
https://netapp.atlassian.net/wiki/spaces/OSSG/pages/608854350/CloudStack+API

Every call is timed end-to-end (including async job polling, since that is
part of the wall-clock cost an operator/automation actually pays) and the
elapsed time is handed back to the caller so benchmark scripts can log it.
"""

import logging
import time

import requests

log = logging.getLogger("cloudstack_client")


class CloudStackAPIError(Exception):
    """Raised when CloudStack returns an errorcode/errortext, or the HTTP
    call itself fails validation."""

    def __init__(self, command, detail):
        self.command = command
        self.detail = detail
        super().__init__(f"{command} failed: {detail}")


class CloudStackClient:
    """Thin wrapper around the CloudStack query API.

    A single instance (and its underlying requests.Session) is safe to share
    across threads for concurrency benchmarks: we never mutate session state
    after login, and urllib3's connection pool is designed for concurrent
    use.
    """

    def __init__(
        self,
        api_url,
        username,
        password,
        verify_ssl=True,
        http_timeout_sec=30,
        job_timeout_sec=300,
        job_poll_interval_sec=1.5,
    ):
        self.api_url = api_url.rstrip("/")
        self.http_timeout_sec = http_timeout_sec
        self.job_timeout_sec = job_timeout_sec
        self.job_poll_interval_sec = job_poll_interval_sec

        self.session = requests.Session()
        self.session.verify = verify_ssl
        if not verify_ssl:
            requests.packages.urllib3.disable_warnings(
                requests.packages.urllib3.exceptions.InsecureRequestWarning
            )

        self.sessionkey = None
        self._login(username, password)

    def _login(self, username, password):
        params = {
            "command": "login",
            "username": username,
            "password": password,
            "response": "json",
        }
        resp = self.session.post(self.api_url, data=params, timeout=self.http_timeout_sec)
        resp.raise_for_status()
        data = resp.json()
        login_resp = data.get("loginresponse")
        if not login_resp or "sessionkey" not in login_resp:
            raise CloudStackAPIError("login", data)
        self.sessionkey = login_resp["sessionkey"]
        log.info("Logged in as %s, session established", username)

    def call(self, command, params=None, poll_async=True):
        """Issue one CloudStack API command.

        Returns a tuple (payload, elapsed_sec). `elapsed_sec` covers the
        initial HTTP round trip AND any async job polling (i.e. the full
        wall-clock time a caller would observe waiting for the operation to
        finish).
        """
        req_params = dict(params or {})
        req_params["command"] = command
        req_params["response"] = "json"
        req_params["sessionkey"] = self.sessionkey

        start = time.perf_counter()
        resp = self.session.post(self.api_url, data=req_params, timeout=self.http_timeout_sec)
        resp.raise_for_status()
        data = resp.json()

        # Most commands wrap their payload as {"<command>response": {...}}, but a
        # few (e.g. enableStorageMaintenance -> prepareprimarystorageformaintenanceresponse)
        # use a legacy internal command name instead. Since CloudStack always wraps
        # the payload in exactly one top-level key, fall back to "the sole value"
        # rather than assuming the key name matches the command.
        resp_key = f"{command.lower()}response"
        if resp_key in data:
            payload = data[resp_key]
        elif len(data) == 1:
            payload = next(iter(data.values()))
        else:
            payload = data

        if isinstance(payload, dict) and "errorcode" in payload and "jobid" not in payload:
            raise CloudStackAPIError(command, payload.get("errortext", payload))

        if poll_async and isinstance(payload, dict) and "jobid" in payload:
            payload = self._poll_job(payload["jobid"])

        elapsed = time.perf_counter() - start
        return payload, elapsed

    def _poll_job(self, jobid):
        deadline = time.time() + self.job_timeout_sec
        while time.time() < deadline:
            params = {
                "command": "queryAsyncJobResult",
                "jobid": jobid,
                "response": "json",
                "sessionkey": self.sessionkey,
            }
            resp = self.session.post(self.api_url, data=params, timeout=self.http_timeout_sec)
            resp.raise_for_status()
            job = resp.json().get("queryasyncjobresultresponse", {})
            status = job.get("jobstatus", 0)
            if status == 1:
                return job.get("jobresult", job)
            if status == 2:
                raise CloudStackAPIError(
                    "queryAsyncJobResult", job.get("jobresult", job)
                )
            time.sleep(self.job_poll_interval_sec)
        raise TimeoutError(f"Async job {jobid} did not complete within {self.job_timeout_sec}s")
