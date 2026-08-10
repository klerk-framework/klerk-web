// Progressive enhancement for klerk-web forms.
//
// Every lookup is scoped to the form element, so any number of forms may live on the same page. Without this script
// the form still posts and the server validates; the script only adds validation while the user types.

(function () {
    "use strict";

    function bind(form) {
        if (form.dataset.klerkBound === "true") {
            return;
        }
        form.dataset.klerkBound = "true";
        form.addEventListener("change", function () {
            validate(form);
        });
    }

    function submitButton(form) {
        return form.querySelector("[data-klerk-submit]");
    }

    function errorMessages(form) {
        return form.querySelector("[data-klerk-errormessages]");
    }

    function clearErrors(form) {
        form.querySelectorAll("input, select, textarea").forEach(function (input) {
            if (typeof input.setCustomValidity === "function") {
                input.setCustomValidity("");
            }
            input.setAttribute("aria-invalid", "false");
        });
        form.querySelectorAll("[data-error-for]").forEach(function (span) {
            span.innerText = "";
            span.style.visibility = "hidden";
        });
        var messages = errorMessages(form);
        if (messages !== null) {
            messages.replaceChildren();
        }
    }

    // propertyProblems is a list of {field, humanReadable}. A null field means the problem is not tied to an input.
    function handleProblems(form, propertyProblems) {
        var unattached = [];
        propertyProblems.forEach(function (problem) {
            var message = problem.humanReadable;
            if (problem.field === null || problem.field === undefined) {
                unattached.push(message);
                return;
            }
            var selector = CSS.escape(problem.field);
            var input = form.querySelector('[name="' + selector + '"]');
            if (input !== null) {
                if (typeof input.setCustomValidity === "function") {
                    input.setCustomValidity(message);
                }
                input.setAttribute("aria-invalid", "true");
            }
            var errorSpan = form.querySelector('[data-error-for="' + selector + '"]');
            if (errorSpan !== null) {
                errorSpan.innerText = message;
                errorSpan.style.visibility = "visible";
            } else if (input === null) {
                unattached.push(message);
            }
        });
        return unattached;
    }

    function handleCollectionProblems(form, collectionProblems) {
        var messages = errorMessages(form);
        if (messages === null) {
            return;
        }
        messages.replaceChildren();
        collectionProblems.forEach(function (problem) {
            var line = document.createElement("div");
            line.innerText = problem;
            messages.appendChild(line);
        });
    }

    function showFormProblem(form, text) {
        var messages = errorMessages(form);
        if (messages === null) {
            return;
        }
        messages.replaceChildren();
        var line = document.createElement("div");
        line.innerText = text;
        messages.appendChild(line);
    }

    function validate(form) {
        var XHR = new XMLHttpRequest();
        var FD = new FormData(form);
        var button = submitButton(form);

        XHR.addEventListener("load", function (event) {
            clearErrors(form);
            if (event.target.status === 200) {
                if (button !== null) {
                    button.disabled = false;
                }
                return;
            }
            if (!event.target.response) {
                // An error we cannot explain: let the user submit and have the server decide.
                if (button !== null) {
                    button.disabled = false;
                }
                return;
            }
            var response = JSON.parse(event.target.response);
            var propertyProblems = response.propertyProblems || [];
            var collectionProblems = response.propertyCollectionProblems || [];
            var dryRunProblems = response.dryRunProblems || [];
            var unattached = handleProblems(form, propertyProblems);
            handleCollectionProblems(form, collectionProblems.concat(dryRunProblems).concat(unattached));
            var hasErrors = propertyProblems.length > 0 ||
                collectionProblems.length > 0 ||
                dryRunProblems.length > 0;
            if (button !== null) {
                button.disabled = hasErrors;
            }
        });

        // The validation is an enhancement. If it cannot run, the user must still be able to submit and let the
        // server validate - never leave the button disabled without saying why.
        XHR.addEventListener("error", function () {
            if (button !== null) {
                button.disabled = false;
            }
            showFormProblem(form, "Could not reach the server to check the form. You can still submit it.");
        });

        var formPath = form.getAttribute("action");
        var separator = formPath.includes("?") ? "&" : "?";
        XHR.open("POST", formPath + separator + "dryRun=true&onlyErrors=true");
        XHR.send(FD);
    }

    function bindAll() {
        document.querySelectorAll("form[data-klerk-form]").forEach(bind);
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", bindAll);
    } else {
        bindAll();
    }
})();
