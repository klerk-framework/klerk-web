function validate() {
    const form = document.getElementById("eventForm")
    const XHR = new XMLHttpRequest();
    const FD = new FormData(form);

    XHR.addEventListener("load", (event) => {
        // clear all errors
        document.querySelectorAll('input').forEach(input => {
            input.setCustomValidity("");
            input.setAttribute('aria-invalid', 'false');
            var errormessageId = input.getAttribute('aria-errormessage');
            if (errormessageId !== null) {
                var errorSpan = document.getElementById(errormessageId);
                if (errorSpan == null) {
                } else {
                    errorSpan.innerText = '';
                    errorSpan.style.visibility = 'hidden';
                }
            }
        });

        document.getElementById("errormessages").replaceChildren();
        const submitButton = document.getElementById("submitButton");
        if (event.target.status == 200) {
            submitButton.disabled = false;
            return;
        }
        if (event.target.response) {
            const response = JSON.parse(event.target.response);
            handleProblems(response.propertyProblems);
            handleCollectionProblems(response.propertyCollectionProblems);
            const hasErrors = Object.keys(response.propertyProblems).length > 0 || response.propertyCollectionProblems.length > 0 || response.formProblems.length > 0 || response.dryRunProblems.length > 0;
            submitButton.disabled = hasErrors;
        }
    });

    XHR.addEventListener("error", (event) => {
        console.log('Oops! Validation went wrong.');
    });

    const formPath = form.getAttribute('action');
    const separator = formPath.includes("?") ? "&" : "?";
    const url = formPath + separator + "dryRun=true&onlyErrors=true";

    XHR.open("POST", url);
    XHR.send(FD);
}


function handleProblems(propertyProblems) {
    for (var key in propertyProblems) {
        if (key == null) {
            continue;
        }
        var value = propertyProblems[key];
        var input = document.getElementById(key);
        input.setCustomValidity(value);
        input.setAttribute('aria-invalid', 'true');
        input.style.visibility = 'visible';
        var errorSpan = document.getElementById("error-" + key);
        if (errorSpan !== null) {
            errorSpan.innerText = value;
            errorSpan.style.visibility = 'visible';
        }
    }
}

function handleCollectionProblems(collectionProblems) {
    document.getElementById("errormessages").replaceChildren(collectionProblems);
}

function toggleNullableFields(response) {
    response.fieldsMustBeNull.forEach(f => {
        document.getElementById(f).disabled = true;
        document.getElementById("label-" + f).style = "opacity: 0.5;";
    });

    response.fieldsMustNotBeNull.forEach(f => {
        document.getElementById(f).disabled = false;
        document.getElementById("label-" + f).style = "opacity: 1.0;";
    });

}
