"""Locust workload for the local Loan Package Search service.

Run Locust with the project as its working directory and host http://localhost:8080.
The PDF task requests a single-byte HTTP range: the supplied PDF is large, so this
exercises the endpoint without repeatedly downloading the whole file. Search and
page APIs are exercised with normal response bodies.
"""

import random
from urllib.parse import quote

from locust import HttpUser, between, task


SEARCH_QUERIES = (
    "cash",
    '"cash to close"',
    "bike OR car",
    "borrower AND income",
    "sign*",
    "payment AND date",
    "property OR address",
)


class LoanPackageReviewer(HttpUser):
    """A reviewer browses the package, searches, opens hits, and previews the PDF."""

    wait_time = between(1, 3)

    def on_start(self):
        self.page_id = None

    @task(2)
    def package_metadata(self):
        with self.client.get(
            "/api/package", name="/api/package", catch_response=True
        ) as response:
            if response.status_code != 200:
                response.failure(f"package endpoint returned {response.status_code}")
                return
            try:
                payload = response.json()
                if "documents" not in payload or "pageCount" not in payload:
                    response.failure("package response is missing expected fields")
                else:
                    response.success()
            except ValueError:
                response.failure("package endpoint returned invalid JSON")

    @task(6)
    def search_package(self):
        query = random.choice(SEARCH_QUERIES)
        offset = random.choice((0, 0, 0, 20))
        with self.client.get(
            "/api/search",
            params={"q": query, "limit": 20, "offset": offset},
            name="/api/search",
            catch_response=True,
        ) as response:
            if response.status_code != 200:
                response.failure(f"search endpoint returned {response.status_code}")
                return
            try:
                payload = response.json()
                if "results" not in payload or "totalHits" not in payload:
                    response.failure("search response is missing expected fields")
                    return
                if payload["results"]:
                    self.page_id = random.choice(payload["results"])["pageId"]
                response.success()
            except (ValueError, KeyError, TypeError):
                response.failure("search endpoint returned an invalid response")

    @task(2)
    def open_result_page(self):
        # Use a page returned by search to avoid expected 404s for blank scanned pages.
        if not self.page_id:
            return
        page_path = quote(self.page_id, safe="")
        with self.client.get(
            f"/api/pages/{page_path}",
            name="/api/pages/{pageId}",
            catch_response=True,
        ) as response:
            if response.status_code != 200:
                response.failure(f"page endpoint returned {response.status_code}")
                return
            try:
                payload = response.json()
                if payload.get("pageId") != self.page_id or not payload.get("text"):
                    response.failure("page response is missing page text or metadata")
                else:
                    response.success()
            except ValueError:
                response.failure("page endpoint returned invalid JSON")

    @task(1)
    def preview_pdf(self):
        # The source PDF is ~137 MB; request one byte rather than downloading the
        # whole document on every simulated preview.
        with self.client.get(
            "/api/pdf",
            headers={"Range": "bytes=0-0"},
            name="/api/pdf [1-byte range]",
            catch_response=True,
        ) as response:
            if response.status_code != 206:
                response.failure(f"PDF endpoint returned {response.status_code}")
                return
            if "application/pdf" not in response.headers.get("Content-Type", ""):
                response.failure("PDF endpoint did not return application/pdf")
                return
            if len(response.content) != 1 or not response.headers.get("Content-Range", "").startswith("bytes 0-0/"):
                response.failure("PDF endpoint did not return the requested byte range")
            else:
                response.success()
