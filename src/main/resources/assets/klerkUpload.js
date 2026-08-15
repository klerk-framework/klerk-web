// Uploads a chosen file before the form is submitted, and puts the resulting upload id in a hidden field.
//
// Speaks tus 1.0 against the routes registered by uploadRoutes: POST to create, PATCH to append, HEAD to find out
// where to resume. Chunked, so a dropped connection costs one chunk rather than the whole file.
//
// Without this script the form still works: the file input keeps its name and posts with the form, and the server
// does the upload in one request.
(function () {
    "use strict";

    var CHUNK_SIZE = 5 * 1024 * 1024;
    var MAX_ATTEMPTS = 5;

    function csrfToken(form) {
        var input = form.querySelector('input[name^="csrf-token"], input[name^="__Host-csrf-token"]');
        return input ? input.value : null;
    }

    function csrfHeaderName(form) {
        var input = form.querySelector('input[name^="csrf-token"], input[name^="__Host-csrf-token"]');
        return input ? input.name : null;
    }

    function base64(value) {
        return btoa(unescape(encodeURIComponent(value)));
    }

    function metadataHeader(file) {
        return "filename " + base64(file.name) + ",contentType " + base64(file.type || "application/octet-stream");
    }

    function sleep(ms) {
        return new Promise(function (resolve) { setTimeout(resolve, ms); });
    }

    // Creates the upload and returns its URL.
    function create(form, basePath, file) {
        var headers = {"Tus-Resumable": "1.0.0", "Upload-Length": String(file.size), "Upload-Metadata": metadataHeader(file)};
        headers[csrfHeaderName(form)] = csrfToken(form);
        return fetch(basePath, {method: "POST", headers: headers, credentials: "same-origin"}).then(function (response) {
            if (!response.ok) {
                throw new Error("Could not start the upload (" + response.status + ")");
            }
            return response.headers.get("Location");
        });
    }

    // Asks the server where the upload actually is. Used to resume after a failed chunk.
    function currentOffset(url) {
        return fetch(url, {method: "HEAD", headers: {"Tus-Resumable": "1.0.0"}, credentials: "same-origin"})
            .then(function (response) {
                if (!response.ok) {
                    throw new Error("The upload is gone (" + response.status + ")");
                }
                return parseInt(response.headers.get("Upload-Offset"), 10);
            });
    }

    function sendChunk(form, url, file, offset) {
        var chunk = file.slice(offset, Math.min(offset + CHUNK_SIZE, file.size));
        var headers = {
            "Tus-Resumable": "1.0.0",
            "Content-Type": "application/offset+octet-stream",
            "Upload-Offset": String(offset)
        };
        headers[csrfHeaderName(form)] = csrfToken(form);
        return fetch(url, {method: "PATCH", headers: headers, body: chunk, credentials: "same-origin"})
            .then(function (response) {
                if (response.status === 409) {
                    // Somebody else got there first, or a chunk landed after we gave up on it. Resume from the truth.
                    return parseInt(response.headers.get("Upload-Offset"), 10);
                }
                if (!response.ok) {
                    throw new Error("Chunk rejected (" + response.status + ")");
                }
                return parseInt(response.headers.get("Upload-Offset"), 10);
            });
    }

    function upload(form, basePath, file, onProgress) {
        return create(form, basePath, file).then(function (url) {
            var offset = 0;
            var attempts = 0;

            function step() {
                if (offset >= file.size) {
                    return Promise.resolve(url);
                }
                return sendChunk(form, url, file, offset).then(function (next) {
                    offset = next;
                    attempts = 0;
                    onProgress(offset, file.size);
                    return step();
                }).catch(function (error) {
                    attempts += 1;
                    if (attempts >= MAX_ATTEMPTS) {
                        throw error;
                    }
                    // Back off, ask where the upload really is, and carry on from there.
                    return sleep(500 * attempts).then(function () {
                        return currentOffset(url);
                    }).then(function (actual) {
                        offset = actual;
                        return step();
                    });
                });
            }

            return step();
        });
    }

    function idFromUrl(url) {
        return url.substring(url.lastIndexOf("/") + 1);
    }

    function bind(form) {
        var basePath = form.getAttribute("data-klerk-upload-path");
        var fileInputs = form.querySelectorAll("input[data-klerk-file]");
        if (!basePath || fileInputs.length === 0) {
            return;
        }

        Array.prototype.forEach.call(fileInputs, function (input) {
            var property = input.getAttribute("data-klerk-file");
            var hidden = form.querySelector('input[data-klerk-upload-id="' + property + '"]');
            var progress = form.querySelector('[data-klerk-upload-progress="' + property + '"]');
            var submit = form.querySelector("[data-klerk-submit]");

            input.addEventListener("change", function () {
                var file = input.files && input.files[0];
                if (!file) {
                    hidden.value = "";
                    return;
                }
                if (submit) {
                    submit.disabled = true;
                }
                upload(form, basePath, file, function (sent, total) {
                    if (progress) {
                        progress.textContent = Math.floor((sent / total) * 100) + "%";
                    }
                }).then(function (url) {
                    hidden.value = idFromUrl(url);
                    hidden.name = property;
                    // The bytes are on the server already; sending them again with the form would double the work.
                    input.removeAttribute("name");
                    if (progress) {
                        progress.textContent = "";
                    }
                }).catch(function (error) {
                    if (progress) {
                        progress.textContent = String(error.message || error);
                    }
                    // Leave the file input named, so submitting still works the slow way.
                }).then(function () {
                    if (submit) {
                        submit.disabled = false;
                    }
                });
            });
        });
    }

    function bindAll() {
        Array.prototype.forEach.call(document.querySelectorAll("form[data-klerk-upload-path]"), bind);
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", bindAll);
    } else {
        bindAll();
    }
})();
