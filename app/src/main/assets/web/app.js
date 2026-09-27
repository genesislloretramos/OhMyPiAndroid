/*
 * The chat, in a browser on the phone that is running the agent.
 *
 * No framework, no bundler, no CDN, no build step: this file is served as it is written and is
 * read by the phone. Everything below is either a fetch, a piece of the DOM, or the twenty lines
 * of framing that server-sent events need when you are reading them off a `fetch` body rather than
 * an `EventSource`.
 *
 * The wire format is Server-Sent Events, which is the grammar `omp.agent.http.Sse` in the app's
 * own core module already parses on the client side: `data:` lines, a blank line ending an event,
 * and a final `data: [DONE]`. `EventSource` would read it for free, but it is GET-only and the
 * question belongs in a request body, so the same grammar is read here instead — the rules it
 * follows, including several `data:` lines in one event joined with a newline, are the rules in
 * that class's documentation.
 */
(function () {
  "use strict";

  var state = null;
  var current = null;
  var transcript = null;
  var turn = null;          // the id of the question being answered
  var approval = null;      // the id of the write being asked about
  var live = null;          // the element the answer is streaming into
  var naming = false;       // whether the New button is asking for a name or making one

  var el = {
    state: document.getElementById("state"),
    list: document.getElementById("list"),
    log: document.getElementById("log"),
    where: document.getElementById("where"),
    composer: document.getElementById("composer"),
    send: document.getElementById("send"),
    stop: document.getElementById("stop"),
    newName: document.getElementById("new-name"),
    newButton: document.getElementById("new"),
    cancelNew: document.getElementById("new-cancel"),
    theme: document.getElementById("theme"),
    gate: document.getElementById("gate"),
    gateTitle: document.getElementById("gate-title"),
    gateLines: document.getElementById("gate-lines"),
    allow: document.getElementById("allow"),
    decline: document.getElementById("decline")
  };

  // ---- the wire ------------------------------------------------------------------------------

  function api(path, options) {
    var settings = Object.assign({ credentials: "same-origin" }, options || {});
    settings.headers = Object.assign({ "Content-Type": "application/json" }, settings.headers);
    return fetch(path, settings).then(function (reply) {
      if (reply.status === 401) {
        // The cookie is gone or was never set. Back to the unlock card rather than a page of
        // failed fetches: the only way in is the token, and the only place to type it is there.
        window.location.replace("/login");
        throw new Error("no token");
      }
      if (reply.status === 204) return null;
      return reply.text().then(function (text) {
        if (!reply.ok) {
          var said = "";
          try { said = JSON.parse(text).error || ""; } catch (e) { said = text.trim(); }
          throw new Error(said || (reply.status + " from " + path));
        }
        return text ? JSON.parse(text) : null;
      });
    });
  }

  /**
   * One event off a server-sent-events stream.
   *
   * The rules are the ones the grammar actually has: a line starting with a colon is a comment and
   * is dropped, a line with no colon is a field with an empty value, one leading space after the
   * colon is not part of the value, and several `data:` lines in one event are joined with a
   * newline. `[DONE]` ends the stream rather than being an event.
   */
  function takeEvent(raw) {
    var name = "message";
    var data = "";
    var lines = raw.split("\n");
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (!line || line.charAt(0) === ":") continue;
      var colon = line.indexOf(":");
      var field = colon < 0 ? line : line.substring(0, colon);
      var value = colon < 0 ? "" : line.substring(colon + 1);
      if (value.charAt(0) === " ") value = value.substring(1);
      if (field === "data") data += (data ? "\n" : "") + value;
      else if (field === "event") name = value;
    }
    return { name: name, data: data };
  }

  function stream(path, body, onEvent) {
    return fetch(path, {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    }).then(function (reply) {
      if (!reply.ok || !reply.body) {
        return reply.text().then(function (text) {
          var said = "";
          try { said = JSON.parse(text).error || ""; } catch (e) { said = text.trim(); }
          throw new Error(said || (reply.status + " from " + path));
        });
      }
      var reader = reply.body.getReader();
      var decoder = new TextDecoder();
      var held = "";
      var finished = false;
      function pump() {
        return reader.read().then(function (part) {
          if (part.done) return;
          held += decoder.decode(part.value, { stream: true });
          var cut;
          while ((cut = held.indexOf("\n\n")) >= 0) {
            var raw = held.substring(0, cut);
            held = held.substring(cut + 2);
            var got = takeEvent(raw);
            if (got.data === "[DONE]") { finished = true; continue; }
            if (!got.data || finished) continue;
            onEvent(got.name, JSON.parse(got.data));
          }
          // Reading goes on past [DONE], all the way to the end of the body. Cancelling the
          // reader, or simply dropping it, aborts the request underneath and the browser records
          // a net::ERR_ABORTED for a stream that arrived perfectly well — and a console full of
          // errors is how a real one gets missed. The server closes the connection after [DONE],
          // so the next read is the one that ends it.
          return pump();
        });
      }
      return pump();
    });
  }

  // ---- rendering -----------------------------------------------------------------------------

  function text(tag, className, body) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (body !== undefined && body !== null) node.textContent = body;
    return node;
  }

  function say(what) {
    el.state.textContent = what;
  }

  function when(millis) {
    var then = new Date(millis);
    var today = new Date();
    var sameDay = then.toDateString() === today.toDateString();
    return sameDay
      ? then.toTimeString().slice(0, 5)
      : then.toISOString().slice(5, 10).replace("-", "/");
  }

  function drawState() {
    if (!state) return;
    say(state.version + " on " + state.hostRoot);
    el.where.textContent = "";
    el.where.appendChild(text("span", null, "Conversations live in this folder on the phone:"));
    var path = text("code", "path", state.hostRoot);
    el.where.appendChild(path);
    var copy = text("button", "quiet", "Copy path");
    copy.addEventListener("click", function () {
      if (navigator.clipboard) navigator.clipboard.writeText(state.hostRoot);
      say("path copied");
    });
    el.where.appendChild(copy);
  }

  function drawList() {
    el.list.textContent = "";
    if (!state.conversations.length) {
      el.list.appendChild(text("p", "empty", "No conversations yet. Make one above."));
      return;
    }
    for (var i = 0; i < state.conversations.length; i++) {
      var one = state.conversations[i];
      var button = text("button", "conversation");
      button.setAttribute("aria-current", one.name === current ? "true" : "false");
      var row = text("div", "row");
      row.appendChild(text("span", "title", one.title || one.name));
      row.appendChild(text("span", "when", when(one.modified)));
      button.appendChild(row);
      button.appendChild(text("div", "sub", one.hostPath));

      var flags = text("div", "sub");
      if (!one.configured) {
        flags.appendChild(text("span", "flag", "no provider, model or endpoint set"));
      } else if (!one.keyStored) {
        flags.appendChild(text("span", "flag", "no key for " + one.provider));
      } else {
        flags.appendChild(text("span", "flag good", one.provider + " / " + one.model));
      }
      button.appendChild(flags);

      button.addEventListener("click", function () { open(one.name); });
      el.list.appendChild(button);
    }
  }

  function drawLog() {
    el.log.textContent = "";
    if (!transcript || !transcript.entries.length) {
      var where = current && state
        ? state.hostRoot + "/" + current
        : "a conversation";
      el.log.appendChild(text("p", "empty",
        "Nothing here yet. A conversation is a plain folder at " + where +
        ", and every question and answer is written into it."));
      return;
    }
    for (var i = 0; i < transcript.entries.length; i++) {
      el.log.appendChild(entry(transcript.entries[i]));
    }
    if (transcript.total > transcript.shown) {
      el.log.appendChild(text("p", "entry system",
        "Showing the last " + transcript.shown + " of " + transcript.total +
        " entries. The whole transcript is in " + transcript.transcriptPath + "."));
    }
    for (var s = 0; s < transcript.skipped.length; s++) {
      el.log.appendChild(text("p", "entry system",
        "A line at byte " + transcript.skipped[s].offset + " was left out: " +
        transcript.skipped[s].reason + "."));
    }
    scroll();
  }

  function entry(one) {
    var node = text("article", "entry " + role(one.role));
    node.appendChild(text("div", "who", role(one.role)));
    var body = text("div", "body", one.content);
    node.appendChild(body);
    if (one.tool) node.appendChild(text("div", "who", one.tool));
    if (one.approved) node.appendChild(text("div", "who", "approved: " + one.approved));
    return node;
  }

  function role(wire) {
    if (wire === "user") return "user";
    if (wire === "assistant") return "assistant";
    if (wire === "tool-result") return "tool";
    return "system";
  }

  function scroll() {
    el.log.scrollTop = el.log.scrollHeight;
  }

  // ---- the conversation ----------------------------------------------------------------------

  function refresh() {
    return api("/api/state").then(function (got) {
      state = got;
      drawState();
      drawList();
      return state;
    });
  }

  function open(name) {
    if (turn) return;
    current = name;
    return api("/api/conversations/" + encodeURIComponent(name) + "/transcript")
      .then(function (got) {
        transcript = got;
        drawList();
        drawLog();
        say(name + " — " + got.total + " entries");
      })
      .catch(function (why) { say(why.message); });
  }

  /** One button, two jobs: ask for a name, then make the folder with it. */
  function newButton() {
    if (!naming) {
      naming = true;
      el.newName.hidden = false;
      el.newName.value = "";
      el.newName.focus();
      el.newButton.textContent = "Make";
      el.cancelNew.hidden = false;
      return;
    }
    el.newButton.disabled = true;
    api("/api/conversations", { method: "POST", body: JSON.stringify({ name: el.newName.value.trim() }) })
      .then(function (made) {
        el.newButton.disabled = false;
        resetNew();
        return refresh().then(function () { return open(made.name); });
      })
      .catch(function (why) { el.newButton.disabled = false; say(why.message); });
  }

  function resetNew() {
    naming = false;
    el.newName.hidden = true;
    el.newName.value = "";
    el.newButton.textContent = "New";
    el.cancelNew.hidden = true;
  }

  // ---- one question --------------------------------------------------------------------------

  function ask(text_) {
    if (!current) { say("Open a conversation first"); return; }
    if (turn) { say("Already answering a question"); return; }
    // What the transcript held before this question, so that a turn which recorded nothing —
    // a refusal before the endpoint was ever reached — does not get redrawn into an empty log.
    var before = transcript ? transcript.total : 0;

    el.composer.value = "";
    var stream_ = text("article", "entry stream");
    stream_.appendChild(text("div", "who", "omp"));
    var body = text("div", "body");
    stream_.appendChild(body);
    var notices = text("div", "notices");
    stream_.appendChild(notices);
    el.log.appendChild(stream_);
    // `line` is the one line being written to right now, and it is replaced as the model streams.
    // `notices` is everything discrete — a refusal, an error, how the turn ended — which is
    // appended to and never replaces the line above it.
    live = { root: stream_, body: body, notices: notices, line: null };
    scroll();

    el.send.disabled = true;
    say("asking " + current + "…");

    stream("/api/conversations/" + encodeURIComponent(current) + "/message", { text: text_ }, onEvent)
      .then(function () { finish(before); })
      .catch(function (why) { onEvent("error", { text: why.message }); finish(before); });
  }

  function onEvent(name, data) {
    if (name === "open") {
      turn = data.turn;
      el.stop.hidden = false;
      return;
    }
    if (name === "text") {
      // The model's own words, arriving. The line is flushed on the agent's line breaks, so a
      // paragraph is a paragraph and not one long strip.
      if (live.line) live.line.remove();
      live.line = text("div", "line", data.text);
      live.body.appendChild(live.line);
      scroll();
      return;
    }
    if (name === "note") {
      if (live.line) live.line.remove();
      live.line = text("div", "line note", data.text);
      live.body.appendChild(live.line);
      scroll();
      return;
    }
    if (name === "refusal") { notice(data.text); return; }
    if (name === "error") { notice(data.text); return; }
    if (name === "approval") { openGate(data); return; }
    if (name === "answered") { closeGate(); return; }
    if (name === "turn") { notice(data.message); return; }
  }

  /** One discrete line, kept above the next one rather than overwriting it. */
  function notice(text_) {
    if (!live) return;
    if (live.line) live.line.remove();
    live.line = null;
    live.notices.appendChild(text("div", "line note", text_));
    scroll();
  }

  function finish(before) {
    turn = null;
    live = null;
    el.send.disabled = false;
    el.stop.hidden = true;
    if (!current) return;
    var name = current;
    api("/api/conversations/" + encodeURIComponent(name) + "/transcript")
      .then(function (got) {
        if (got.total === before) {
          // Nothing was appended, so the transcript has nothing to say about this turn and
          // redrawing would throw away the only account of it there is. What the agent said on
          // the way to refusing stays on screen, and the state line says what ended it.
          say(name + " — nothing was added to the transcript");
          return;
        }
        transcript = got;
        drawLog();
        say(name + " — " + got.total + " entries");
        return refresh();
      })
      .catch(function () { /* the log already shows what arrived */ });
  }

  // ---- the approval --------------------------------------------------------------------------

  /**
   * A write the agent wants to make.
   *
   * The dialog has no way out except its two buttons. It does not close on the backdrop, it does
   * not close on Escape, and it does not close on a timer, because a tap that lands on a phone
   * held in one hand lands somewhere, and "somewhere" must not be a yes to a file being written.
   */
  function openGate(data) {
    approval = data.id;
    el.gateTitle.textContent = "The agent wants to write to this conversation";
    el.gateLines.textContent = (data.lines || []).join("\n");
    el.gate.hidden = false;
    el.allow.focus();
  }

  function closeGate() {
    approval = null;
    el.gate.hidden = true;
  }

  function answer(allow) {
    if (!approval) return;
    var id = approval;
    // Held shut until the server has taken the answer, so a second tap cannot answer twice.
    el.allow.disabled = true;
    el.decline.disabled = true;
    api("/api/approvals/" + encodeURIComponent(id), {
      method: "POST",
      body: JSON.stringify({ allow: allow })
    }).then(function () {
      el.allow.disabled = false;
      el.decline.disabled = false;
      closeGate();
    }).catch(function (why) {
      el.allow.disabled = false;
      el.decline.disabled = false;
      closeGate();
      say(why.message);
    });
  }

  // ---- the theme -----------------------------------------------------------------------------

  function theme() {
    var chosen = "auto";
    try { chosen = window.localStorage.getItem("omp-theme") || "auto"; } catch (e) { }
    document.documentElement.setAttribute("data-theme", chosen);
    el.theme.textContent = chosen === "auto" ? "Auto" : chosen === "light" ? "Light" : "Dark";
  }

  function cycleTheme() {
    var order = ["auto", "light", "dark"];
    var now = document.documentElement.getAttribute("data-theme") || "auto";
    var next = order[(order.indexOf(now) + 1) % order.length];
    try { window.localStorage.setItem("omp-theme", next); } catch (e) { }
    theme();
  }

  // ---- wiring --------------------------------------------------------------------------------

  // The form submits rather than the button being clicked, so Enter and the button cannot both
  // fire and send the same question twice.
  document.getElementById("compose").addEventListener("submit", function (event) {
    event.preventDefault();
    var said = el.composer.value.trim();
    if (said) ask(said);
  });

  el.composer.addEventListener("keydown", function (event) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      var said = el.composer.value.trim();
      if (said) ask(said);
    }
  });

  el.stop.addEventListener("click", function () {
    if (!turn) return;
    api("/api/turns/" + encodeURIComponent(turn) + "/cancel", { method: "POST" })
      .then(function () { say("stopping…"); })
      .catch(function (why) { say(why.message); });
  });

  el.newButton.addEventListener("click", newButton);
  el.cancelNew.addEventListener("click", resetNew);
  el.allow.addEventListener("click", function () { answer(true); });
  el.decline.addEventListener("click", function () { answer(false); });
  el.theme.addEventListener("click", cycleTheme);

  theme();
  refresh().then(function () {
    if (state.conversations.length) open(state.conversations[0].name);
    else drawLog();
  }).catch(function (why) { say(why.message); });
})();
