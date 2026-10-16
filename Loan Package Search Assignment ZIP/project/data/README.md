# Optional manifest input

The service uses the supplied PDF by default. To index multiple documents from extracted text files, place `package.json` here or set `-Dloan.package.manifest=/absolute/path/package.json`.
Paths in `pages` are relative to the manifest. Each page points to one UTF-8 text file.

```json
{
  "loanId": "sample-loan",
  "documents": [{
    "id": "closing-disclosure", "name": "Closing Disclosure", "type": "Disclosure",
    "versions": [{
      "id": "final", "name": "Final",
      "pages": [{"number": 1, "path": "texts/closing-page-1.txt"}]
    }]
  }]
}
```

Missing text files are skipped and listed in the package/search API warnings.
