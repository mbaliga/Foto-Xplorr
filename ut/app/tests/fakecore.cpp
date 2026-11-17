// SPDX-License-Identifier: Apache-2.0
// A tiny stand-in for libfotozcore.so exporting the same C ABI with canned data, so the C++/QML
// glue is tested without a Kotlin/Native toolchain. It deliberately mimics two real behaviours the
// glue must handle: pages of 2 (cursor = next offset) and free-text filtering AFTER paging.
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

extern "C" {

static char *dup(const std::string &s) { char *p = static_cast<char *>(std::malloc(s.size() + 1)); std::memcpy(p, s.c_str(), s.size() + 1); return p; }

struct Handle { int magic = 0xF070; };

void *fotoz_library_open(const char *path) { return (path && std::strstr(path, "/bad/")) ? nullptr : new Handle; }
void fotoz_library_close(void *h) { delete static_cast<Handle *>(h); }
void fotoz_free_string(char *p) { std::free(p); }
int fotoz_status_ok() { return 0; }
int fotoz_status_invalid_handle() { return -1; }
int fotoz_status_bad_request() { return -2; }
int fotoz_status_not_found() { return -3; }
int fotoz_status_not_implemented() { return -4; }
int fotoz_status_io_error() { return -5; }
int fotoz_status_internal_error() { return -6; }

char *fotoz_capabilities() { return dup("{\"schemaVersion\":1}"); }

char *fotoz_folders(void *h, const char *req)
{
    if (!h) return nullptr;
    const bool noVideos = req && std::strstr(req, "\"showVideos\":false");
    if (noVideos) return dup("{\"folders\":[{\"folderKey\":\"1:path:pictures\",\"name\":\"Pictures\",\"itemCount\":2,\"newestMs\":3000}]}");
    return dup("{\"folders\":[{\"folderKey\":\"1:path:pictures\",\"name\":\"Pictures\",\"itemCount\":2,\"newestMs\":3000},"
               "{\"folderKey\":\"1:path:pictures/trip\",\"name\":\"\",\"itemCount\":1,\"newestMs\":1000}]}");
}

struct Row { int id; const char *name; const char *mime; };
static const Row kRows[] = {
    {1, "alpha.jpg", "image/jpeg"}, {2, "beta.png", "image/png"}, {3, "gamma.mp4", "video/mp4"},
    {4, "delta.jpg", "image/jpeg"}, {5, "epsilon.jpg", "image/jpeg"},
};

char *fotoz_query_page(void *h, const char *req, const char *cursor)
{
    if (!h) return nullptr;
    std::string r = req ? req : "";
    if (r.find("\"album\":\"NOPE\"") != std::string::npos) return dup("{\"rows\":[],\"count\":0,\"textFilterApplied\":false,\"error\":\"unknown album\"}");
    const bool hideVideos = r.find("\"showVideos\":false") != std::string::npos;
    std::string q;
    auto qp = r.find("\"query\":\"");
    if (qp != std::string::npos) { qp += 9; q = r.substr(qp, r.find('"', qp) - qp); }
    const int offset = cursor ? std::atoi(cursor) : 0, page = 2, total = sizeof(kRows) / sizeof(kRows[0]);
    std::string rows;
    int n = 0;
    for (int i = offset; i < offset + page && i < total; ++i) {
        const Row &row = kRows[i];
        if (hideVideos && std::strncmp(row.mime, "video/", 6) == 0) continue;
        if (!q.empty() && std::string(row.name).find(q) == std::string::npos) continue;   // filter AFTER paging
        if (n++) rows += ",";
        rows += "{\"assetId\":" + std::to_string(row.id) + ",\"displayName\":\"" + row.name + "\",\"mime\":\"" + row.mime +
                "\",\"contentUri\":\"file:///nonexistent/" + row.name + "\",\"bucketName\":\"Pictures\",\"sizeBytes\":2048,\"dateTakenMs\":1700000000000}";
    }
    std::string out = "{\"rows\":[" + rows + "],\"count\":" + std::to_string(n) + ",\"textFilterApplied\":" + (q.empty() ? "false" : "true");
    if (offset + page < total) out += ",\"nextCursor\":\"" + std::to_string(offset + page) + "\"";
    out += "}";
    return dup(out);
}

char *fotoz_asset_detail(void *h, long long) { return h ? dup("{}") : nullptr; }

int fotoz_sync_start(void *h, const char *src, void (*cb)(int, const char *, void *), void *user)
{
    if (!h) return -1;
    std::string s = src ? src : "";
    int status; std::string msg;
    if (s.find("/missing") != std::string::npos) { status = -5; msg = "{\"error\":\"cannot read directory\"}"; }
    else { status = 0; msg = "{\"sourceId\":1,\"itemsSeen\":3,\"fullyEnumerated\":true}"; }
    if (cb) cb(status, msg.c_str(), user);
    return status;
}

} // extern "C"
