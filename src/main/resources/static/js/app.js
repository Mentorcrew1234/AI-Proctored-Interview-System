/*
 * Shared behaviour for the server-rendered pages.
 *
 * Deliberately small, dependency-free and progressive: every page still shows
 * all of its content with JavaScript disabled. This file only collapses,
 * groups and focuses what is already there.
 *
 * Everything is wired by data attribute rather than by id, so a new page picks
 * up the behaviour by using the same markup - there is nothing per-page to
 * remember to register.
 */
(function () {
    'use strict';

    /* ---- sidebar drawer (below the 900px breakpoint) -------------------- */

    function initSidebar() {
        var toggle = document.querySelector('[data-nav-toggle]');
        if (!toggle) {
            return;
        }
        toggle.addEventListener('click', function () {
            var open = document.body.classList.toggle('nav-open');
            toggle.setAttribute('aria-expanded', String(open));
        });
        // Tapping the scrim closes it; so does Escape.
        document.addEventListener('click', function (event) {
            if (!document.body.classList.contains('nav-open')) {
                return;
            }
            if (event.target.closest('.sidebar') || event.target.closest('[data-nav-toggle]')) {
                return;
            }
            document.body.classList.remove('nav-open');
            toggle.setAttribute('aria-expanded', 'false');
        });
    }

    /* ---- tab panels ------------------------------------------------------
     *
     * Used by the detail, report and candidate pages to show one section at a
     * time instead of stacking every section into a very long scroll. The data
     * is already on the page - this only chooses which part of it is visible,
     * so switching a tab costs no request.
     *
     * The chosen tab is mirrored into the URL hash so a refresh, a bookmark or
     * a browser Back keeps the user where they were.
     */

    function initTabs() {
        document.querySelectorAll('[data-tabs]').forEach(function (group) {
            var tabs = Array.prototype.slice.call(group.querySelectorAll('[role="tab"]'));
            if (!tabs.length) {
                return;
            }

            function select(tab, focus) {
                tabs.forEach(function (other) {
                    var selected = other === tab;
                    other.setAttribute('aria-selected', String(selected));
                    other.tabIndex = selected ? 0 : -1;
                    var panel = document.getElementById(other.getAttribute('aria-controls'));
                    if (panel) {
                        panel.hidden = !selected;
                    }
                });
                if (focus) {
                    tab.focus();
                }
            }

            tabs.forEach(function (tab) {
                tab.addEventListener('click', function () {
                    select(tab, false);
                    if (history.replaceState) {
                        history.replaceState(null, '', '#' + tab.getAttribute('aria-controls'));
                    }
                });

                // Arrow-key movement is what makes a tab list usable without a
                // mouse, and is expected of role="tab".
                tab.addEventListener('keydown', function (event) {
                    var index = tabs.indexOf(tab);
                    var next = null;
                    if (event.key === 'ArrowRight') { next = tabs[(index + 1) % tabs.length]; }
                    if (event.key === 'ArrowLeft') { next = tabs[(index - 1 + tabs.length) % tabs.length]; }
                    if (event.key === 'Home') { next = tabs[0]; }
                    if (event.key === 'End') { next = tabs[tabs.length - 1]; }
                    if (next) {
                        event.preventDefault();
                        select(next, true);
                    }
                });
            });

            // Opened from a link that names a tab - either "#panel-completed"
            // or "?tab=completed" - so a sidebar entry can land on the right
            // view without the page having to be split into two routes.
            var wanted = window.location.hash.replace('#', '');
            if (!wanted) {
                var query = new URLSearchParams(window.location.search).get('tab');
                wanted = query ? 'panel-' + query.toLowerCase() : '';
            }
            var requested = wanted
                ? tabs.filter(function (t) { return t.getAttribute('aria-controls') === wanted; })[0]
                : null;
            select(requested || tabs[0], false);
        });
    }

    /* ---- "more actions" menus ------------------------------------------- */

    function closeMenus(except) {
        document.querySelectorAll('.menu-list').forEach(function (list) {
            if (list === except) {
                return;
            }
            list.hidden = true;
            var button = list.parentElement.querySelector('[data-menu]');
            if (button) {
                button.setAttribute('aria-expanded', 'false');
            }
        });
    }

    function initMenus() {
        document.querySelectorAll('[data-menu]').forEach(function (button) {
            var list = button.parentElement.querySelector('.menu-list');
            if (!list) {
                return;
            }
            button.addEventListener('click', function (event) {
                event.stopPropagation();
                var willOpen = list.hidden;
                closeMenus(list);
                list.hidden = !willOpen;
                button.setAttribute('aria-expanded', String(willOpen));
            });
        });

        document.addEventListener('click', function () { closeMenus(null); });
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') {
                closeMenus(null);
            }
        });
    }

    /* ---- invite links ----------------------------------------------------
     *
     * The absolute URL depends on how the application is being served, so it
     * is assembled in the browser rather than guessed on the server.
     */

    function initInviteLinks() {
        document.querySelectorAll('.invite-link').forEach(function (input) {
            input.value = window.location.origin + '/exam/' + input.dataset.token;
        });

        document.querySelectorAll('[data-copy]').forEach(function (button) {
            button.addEventListener('click', function () {
                var target = document.querySelector(button.dataset.copy);
                if (!target) {
                    return;
                }
                target.select();
                var done = function () {
                    var original = button.dataset.originalLabel || button.textContent;
                    button.dataset.originalLabel = original;
                    button.textContent = 'Copied';
                    setTimeout(function () { button.textContent = original; }, 1500);
                };
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(target.value).then(done, function () { done(); });
                } else {
                    document.execCommand('copy');
                    done();
                }
            });
        });
    }

    /* ---- table selection -------------------------------------------------
     *
     * Bulk edit and bulk delete act on whatever is ticked here. The bar only
     * appears once something is selected, so the page is not carrying a large
     * always-open panel that is irrelevant most of the time.
     *
     * Note what this does NOT do: "select all matching" sends only a flag. The
     * server recomputes the matching set from the filter under the caller's own
     * scope, so nothing here decides which interviews an action can reach.
     */

    function initSelection() {
        var root = document.querySelector('[data-selection]');
        if (!root) {
            return;
        }

        var rowBoxes = function () { return root.querySelectorAll('.row-select'); };
        var pageBox = root.querySelector('[data-select-page]');
        var allBox = root.querySelector('[data-select-all-matching]');
        var bar = root.querySelector('[data-selection-bar]');
        var count = root.querySelector('[data-selection-count]');
        var allNotice = root.querySelector('[data-selection-all-notice]');
        var totalMatching = root.dataset.totalMatching || '0';

        function refresh() {
            var selected = root.querySelectorAll('.row-select:checked').length;
            var everything = allBox && allBox.checked;

            if (bar) {
                bar.hidden = !everything && selected === 0;
            }
            if (count) {
                count.textContent = everything
                    ? totalMatching + ' interview(s) matching the current filters'
                    : selected + ' selected';
            }
            if (allNotice) {
                allNotice.hidden = !everything;
            }
            if (pageBox) {
                var boxes = rowBoxes();
                var checkedOnPage = root.querySelectorAll('.row-select:checked').length;
                pageBox.checked = boxes.length > 0 && checkedOnPage === boxes.length;
                pageBox.indeterminate = checkedOnPage > 0 && checkedOnPage < boxes.length;
            }
        }

        if (pageBox) {
            pageBox.addEventListener('change', function () {
                rowBoxes().forEach(function (box) { box.checked = pageBox.checked; });
                if (allBox) {
                    allBox.checked = false;
                }
                refresh();
            });
        }

        if (allBox) {
            allBox.addEventListener('change', function () {
                if (allBox.checked) {
                    rowBoxes().forEach(function (box) { box.checked = false; });
                }
                refresh();
            });
        }

        root.addEventListener('change', function (event) {
            if (event.target.classList.contains('row-select')) {
                if (allBox) {
                    allBox.checked = false;
                }
                refresh();
            }
        });

        // Confirmations. The server re-validates every row regardless; these
        // exist so a destructive action is never one stray click away.
        root.querySelectorAll('[data-confirm-bulk]').forEach(function (button) {
            button.addEventListener('click', function (event) {
                var everything = allBox && allBox.checked;
                var selected = root.querySelectorAll('.row-select:checked').length;
                if (!everything && selected === 0) {
                    event.preventDefault();
                    window.alert('Select at least one interview first.');
                    return;
                }
                var scope = everything
                    ? 'ALL ' + totalMatching + ' interview(s) matching the current filters'
                    : selected + ' selected interview(s)';
                if (!window.confirm(button.dataset.confirmBulk.replace('{scope}', scope))) {
                    event.preventDefault();
                }
            });
        });

        refresh();
    }

    /* ---- one-off confirmations ------------------------------------------ */

    function initConfirms() {
        // Row actions run their own confirmation before narrowing the
        // selection, so they are excluded here rather than prompting twice.
        document.querySelectorAll('[data-confirm]:not([data-select-only])').forEach(function (element) {
            element.addEventListener('click', function (event) {
                if (!window.confirm(element.dataset.confirm)) {
                    event.preventDefault();
                }
            });
        });

        // Guards against an impatient double-click on a slow submit. The server
        // is what actually guarantees the action happens once.
        document.querySelectorAll('[data-submit-once]').forEach(function (form) {
            form.addEventListener('submit', function (event) {
                if (form.dataset.submitted === 'true') {
                    event.preventDefault();
                    return;
                }
                form.dataset.submitted = 'true';
                var button = form.querySelector('[type="submit"]');
                if (button) {
                    button.disabled = true;
                    button.textContent = button.dataset.busyLabel || 'Working...';
                }
            });
        });
    }

    /* ---- generic show/hide panels ---------------------------------------
     *
     * A button carrying data-toggle="#some-panel" shows and hides that panel
     * and keeps aria-expanded honest. Used for the filter panel and the bulk
     * edit fields, both of which are large and irrelevant most of the time -
     * they are on the page, one click away, rather than permanently occupying
     * a screen of scroll.
     */

    function initToggles() {
        document.querySelectorAll('[data-toggle]').forEach(function (button) {
            var panel = document.querySelector(button.dataset.toggle);
            if (!panel) {
                return;
            }
            button.setAttribute('aria-controls', panel.id);
            button.setAttribute('aria-expanded', String(!panel.hidden));

            button.addEventListener('click', function () {
                panel.hidden = !panel.hidden;
                button.setAttribute('aria-expanded', String(!panel.hidden));
                if (!panel.hidden) {
                    var first = panel.querySelector('input, select, textarea, button');
                    if (first) {
                        first.focus();
                    }
                }
            });
        });

        // Opens a panel from somewhere else on the page (the selection bar
        // opening the bulk-edit fields) and scrolls it into view.
        document.querySelectorAll('[data-open]').forEach(function (button) {
            button.addEventListener('click', function () {
                var panel = document.querySelector(button.dataset.open);
                if (!panel) {
                    return;
                }
                panel.hidden = false;
                var owner = document.querySelector('[data-toggle="' + button.dataset.open + '"]');
                if (owner) {
                    owner.setAttribute('aria-expanded', 'true');
                }
                panel.scrollIntoView({ block: 'nearest' });
                var first = panel.querySelector('input, select, textarea');
                if (first) {
                    first.focus();
                }
            });
        });
    }

    /* ---- single-row actions inside the bulk form ------------------------
     *
     * Delete on one row reuses the bulk endpoint, because that endpoint is the
     * one that re-validates every target under the caller's own scope. The row
     * button therefore narrows the selection to itself first. A nested <form>
     * is invalid HTML, which is why this is a formaction submit rather than
     * its own form.
     */

    function initRowActions() {
        document.querySelectorAll('[data-select-only]').forEach(function (button) {
            button.addEventListener('click', function (event) {
                if (button.dataset.confirm && !window.confirm(button.dataset.confirm)) {
                    event.preventDefault();
                    return;
                }
                var root = document.querySelector('[data-selection]');
                if (!root) {
                    return;
                }
                var all = root.querySelector('[data-select-all-matching]');
                if (all) {
                    all.checked = false;
                }
                root.querySelectorAll('.row-select').forEach(function (box) {
                    box.checked = String(box.value) === String(button.dataset.selectOnly);
                });
            });
        });
    }

    /* ---- password visibility toggle ---------------------------------------
     *
     * A button carrying data-toggle-password="#someInput" flips that field
     * between password and text. Purely cosmetic - it only ever changes the
     * input's own type attribute, so a disabled-JS page still has a working
     * password field, just without the toggle.
     */

    function initPasswordToggles() {
        document.querySelectorAll('[data-toggle-password]').forEach(function (button) {
            var input = document.querySelector(button.dataset.togglePassword);
            if (!input) {
                return;
            }
            button.addEventListener('click', function () {
                var revealing = input.type === 'password';
                input.type = revealing ? 'text' : 'password';
                button.textContent = revealing ? 'Hide' : 'Show';
                button.setAttribute('aria-label', revealing ? 'Hide password' : 'Show password');
            });
        });
    }

    /* ---- demo account quick-fill (login page) ------------------------------
     *
     * Fills the sign-in fields with one demo account's credentials - the same
     * ones already printed in plain text next to the button, so this grants
     * nothing a reader could not already type themselves. It never submits the
     * form on its own; signing in is still a deliberate second action.
     */

    function initDemoAccountFill() {
        document.querySelectorAll('[data-fill-account]').forEach(function (button) {
            button.addEventListener('click', function () {
                var email = document.getElementById('username');
                var password = document.getElementById('password');
                if (!email || !password) {
                    return;
                }
                email.value = button.dataset.email || '';
                password.value = button.dataset.password || '';
                password.focus();
            });
        });
    }

    /* ---- login page theme toggle --------------------------------------------
     *
     * Flips .login-wrap between the signature (dark, red accent) theme and a
     * "Classic" one that maps onto the same tokens the rest of the application
     * already uses. Login-only: the markup this looks for exists solely in
     * login.html, so this is a no-op everywhere else, including the other
     * three auth screens that share .login-wrap's base styling.
     *
     * Remembered per browser via localStorage, not sent anywhere - it is a
     * presentation choice for whoever is looking at this screen, not a setting
     * on the account signing in.
     */

    function initThemeToggle() {
        var group = document.querySelector('[data-theme-toggle]');
        var wrap = document.querySelector('.login-wrap');
        if (!group || !wrap) {
            return;
        }

        var STORAGE_KEY = 'proctor.loginTheme';
        var buttons = group.querySelectorAll('[data-set-theme]');

        function apply(theme) {
            wrap.setAttribute('data-theme', theme);
            buttons.forEach(function (button) {
                button.setAttribute('aria-pressed', String(button.dataset.setTheme === theme));
            });
        }

        buttons.forEach(function (button) {
            button.addEventListener('click', function () {
                var theme = button.dataset.setTheme;
                apply(theme);
                try {
                    localStorage.setItem(STORAGE_KEY, theme);
                } catch (e) {
                    // Private browsing or storage disabled - the toggle still
                    // works for this visit, it just will not be remembered.
                }
            });
        });

        var saved = null;
        try {
            saved = localStorage.getItem(STORAGE_KEY);
        } catch (e) {
            // Same as above - fall through to the default.
        }
        apply(saved === 'light' ? 'light' : 'dark');
    }

    /* ---- filter drawer --------------------------------------------------- */

    function initFilters() {
        // Submitting on change would fight with typing in the search box, so
        // only the selects auto-apply; the search box uses its own button.
        document.querySelectorAll('[data-autosubmit]').forEach(function (control) {
            control.addEventListener('change', function () {
                control.form.submit();
            });
        });
    }

    /* ---- candidate pool filter ------------------------------------------

       Narrows the candidate picker on the scheduling forms, in the browser,
       without a round trip - a server-side filter here would re-render the page
       and throw away everything else the user had already typed into the form.

       Three rules make this safe rather than merely convenient:

       1. The filter controls carry no name attribute and live outside the
          submitted field set, so nothing here is ever posted.
       2. The whole panel starts hidden and is revealed only from here, so with
          JavaScript off the full, unfiltered list is offered exactly as before.
       3. A SELECTED candidate is never hidden, however the filter is set.
          Removing one from the DOM would quietly drop them from the submission -
          the form would schedule fewer interviews than the user believed they
          had asked for, with nothing on screen saying so. They stay listed and
          the counter says how many are selected.
    */

    function initPoolFilters() {
        document.querySelectorAll('[data-pool-target]').forEach(function (select) {
            var panel = document.querySelector('[data-pool-filter]');
            if (!panel) {
                return;
            }
            // Every option, in its original order, held as elements so that
            // re-appending one preserves its own selected state.
            var all = Array.prototype.slice.call(select.options);
            var controls = panel.querySelectorAll('[data-pool-key]');
            var counter = panel.querySelector('[data-pool-count]');
            var clear = panel.querySelector('[data-pool-clear]');

            function wanted() {
                var criteria = {};
                controls.forEach(function (control) {
                    if (control.value) {
                        criteria[control.dataset.poolKey] = control.value.toLowerCase();
                    }
                });
                return criteria;
            }

            function has(option, key) {
                return (option.dataset[key] || '').trim().toLowerCase();
            }

            function matches(option, criteria) {
                if (!option.value) {
                    return true; // the "-- select a candidate --" placeholder
                }
                if (criteria.college && has(option, 'college') !== criteria.college) {
                    return false;
                }
                if (criteria.location && has(option, 'location') !== criteria.location) {
                    return false;
                }
                if (criteria.domain && has(option, 'domain') !== criteria.domain) {
                    return false;
                }
                if (criteria.experience && has(option, 'experience') !== criteria.experience) {
                    return false;
                }
                if (criteria.skills) {
                    // Split, never substring: "Java" must not match "JavaScript".
                    var owned = has(option, 'skills').split(',').map(function (part) {
                        return part.trim();
                    });
                    if (owned.indexOf(criteria.skills) === -1) {
                        return false;
                    }
                }
                return true;
            }

            function apply() {
                var criteria = wanted();
                var shown = 0;
                var selected = 0;
                var kept = 0;

                while (select.firstChild) {
                    select.removeChild(select.firstChild);
                }
                all.forEach(function (option) {
                    var ok = matches(option, criteria);
                    if (option.selected && option.value) {
                        selected++;
                        if (!ok) {
                            kept++;
                        }
                    }
                    if (ok || option.selected) {
                        select.appendChild(option);
                        if (option.value) {
                            shown++;
                        }
                    }
                });

                if (counter) {
                    var total = all.filter(function (o) { return o.value; }).length;
                    var text = shown + ' of ' + total + ' candidates shown';
                    if (selected) {
                        text += ' · ' + selected + ' selected';
                    }
                    if (kept) {
                        text += ' (' + kept + ' still listed because selected)';
                    }
                    counter.textContent = text;
                }
            }

            controls.forEach(function (control) {
                control.addEventListener('change', apply);
            });
            if (clear) {
                clear.addEventListener('click', function () {
                    controls.forEach(function (control) {
                        control.value = '';
                    });
                    apply();
                });
            }

            // Revealed only now: without this script the list is unfiltered and
            // the controls would be scenery.
            panel.hidden = false;
            apply();
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        initSidebar();
        initTabs();
        initMenus();
        initInviteLinks();
        initSelection();
        initToggles();
        initRowActions();
        initConfirms();
        initFilters();
        initPoolFilters();
        initPasswordToggles();
        initDemoAccountFill();
        initThemeToggle();
    });
})();
