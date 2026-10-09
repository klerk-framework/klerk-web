// Keyboard navigation for klerk-web tables: j/k and the arrow keys move between rows, Home/End jump to the first and
// last row, Enter opens the row. Each row has one link marked data-klerk-row-link; only one of them is a Tab stop.
//
// The listener is on window, so a page's own handler on document runs first and can claim a key with
// preventDefault().

(function () {
    "use strict";

    if (window.klerkListBound) {
        return;
    }
    window.klerkListBound = true;

    function rowLinks(list) {
        return Array.prototype.slice.call(list.querySelectorAll("a[data-klerk-row-link]"));
    }

    function tabStopIndex(links) {
        return links.findIndex(function (link) { return link.getAttribute("tabindex") === "0"; });
    }

    function setTabStop(links, index) {
        links.forEach(function (link, i) { link.setAttribute("tabindex", i === index ? "0" : "-1"); });
    }

    function init() {
        document.querySelectorAll("[data-klerk-list]").forEach(function (list) {
            var links = rowLinks(list);
            if (links.length > 0) setTabStop(links, Math.max(0, tabStopIndex(links)));
        });
    }

    function isTyping(target) {
        return !!target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName));
    }

    function overlayOpen() {
        try {
            return !!document.querySelector(":popover-open, dialog[open]");
        } catch (ignored) {
            return !!document.querySelector("dialog[open]");
        }
    }

    function nothingFocused(target) {
        return !target || target === document.body || target === document.documentElement;
    }

    /** The list holding the focus, or the first one on the page. */
    function currentList(target) {
        var list = target && target.closest ? target.closest("[data-klerk-list]") : null;
        return list || document.querySelector("[data-klerk-list]");
    }

    function focusRow(links, index) {
        setTabStop(links, index);
        links[index].focus({ preventScroll: true });
        links[index].scrollIntoView({ block: "nearest" });
    }

    function move(list, step) {
        var links = rowLinks(list);
        if (links.length === 0) return false;
        var focused = links.indexOf(document.activeElement);
        // From outside the list, the first move lands on the row that has the Tab stop.
        var next = focused === -1 ? Math.max(0, tabStopIndex(links)) : focused + step;
        focusRow(links, Math.max(0, Math.min(links.length - 1, next)));
        return true;
    }

    function moveToEnd(list, last) {
        var links = rowLinks(list);
        if (links.length === 0) return false;
        focusRow(links, last ? links.length - 1 : 0);
        return true;
    }

    window.addEventListener("keydown", function (e) {
        if (e.defaultPrevented || e.metaKey || e.ctrlKey || e.altKey || isTyping(e.target)) return;
        if (overlayOpen()) return;
        var list = currentList(e.target);
        if (!list) return;
        var inList = list.contains(e.target) && rowLinks(list).indexOf(e.target) !== -1;
        var handled = false;
        switch (e.key) {
            case "j": handled = move(list, 1); break;
            case "k": handled = move(list, -1); break;
            // With focus on some other control, the arrows keep the browser's own behaviour.
            case "ArrowDown": handled = (inList || nothingFocused(e.target)) && move(list, 1); break;
            case "ArrowUp": handled = (inList || nothingFocused(e.target)) && move(list, -1); break;
            case "Home": handled = inList && moveToEnd(list, false); break;
            case "End": handled = inList && moveToEnd(list, true); break;
        }
        if (handled) e.preventDefault();
    });

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
    document.addEventListener("htmx:afterSettle", init);
})();
