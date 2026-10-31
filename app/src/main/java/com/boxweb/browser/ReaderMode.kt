package com.boxweb.browser

/**
 * Reader mode: a script that finds the main text of the page and shows it full screen in a
 * clean, readable layout on top of the page. Running it again (or tapping Close) puts the page back.
 * The page itself is never changed, so nothing needs to be reloaded.
 *
 * The script avoids the dollar sign on purpose: this is a Kotlin raw string.
 */
object ReaderMode {

    const val SCRIPT = """
(function () {
  var ID = '__bw_reader';
  var existing = document.getElementById(ID);
  if (existing) {
    existing.parentNode.removeChild(existing);
    document.documentElement.style.overflow = '';
    return;
  }

  function paragraphChars(el) {
    var total = 0, kids = el.children;
    for (var i = 0; i < kids.length; i++) {
      if (kids[i].tagName === 'P') total += (kids[i].textContent || '').trim().length;
    }
    return total;
  }

  var best = null, bestScore = 0;
  var candidates = document.body.querySelectorAll('article, main, section, div, td');
  for (var i = 0; i < candidates.length; i++) {
    var score = paragraphChars(candidates[i]);
    if (candidates[i].tagName === 'ARTICLE') score = score * 1.3;
    if (score > bestScore) { bestScore = score; best = candidates[i]; }
  }

  var themes = [['#ffffff', '#202124'], ['#f4ecd8', '#3b3226'], ['#16181d', '#d7dae0']];
  var dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  var themeIndex = dark ? 2 : 0;
  var fontSize = 19;

  var box = document.createElement('div');
  box.id = ID;
  box.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:2147483647;' +
    'overflow:auto;-webkit-overflow-scrolling:touch;font-family:Georgia,serif;';

  var style = document.createElement('style');
  style.textContent =
    '#' + ID + ' *{box-sizing:border-box;}' +
    '#' + ID + ' img,#' + ID + ' video{max-width:100%!important;height:auto!important;}' +
    '#' + ID + ' p{margin:0 0 1em!important;line-height:1.65!important;font-size:inherit!important;color:inherit!important;}' +
    '#' + ID + ' h1,#' + ID + ' h2,#' + ID + ' h3,#' + ID + ' h4{line-height:1.25!important;color:inherit!important;margin:1.2em 0 .5em!important;}' +
    '#' + ID + ' a{color:inherit!important;text-decoration:underline!important;}' +
    '#' + ID + ' li,#' + ID + ' blockquote{line-height:1.6!important;color:inherit!important;}' +
    '#' + ID + ' pre{white-space:pre-wrap!important;overflow:auto;font-size:.8em;}' +
    '#' + ID + ' blockquote{margin:1em 0!important;padding-left:1em;border-left:3px solid #8884;}';
  box.appendChild(style);

  var bar = document.createElement('div');
  bar.style.cssText = 'position:sticky;top:0;display:flex;gap:8px;justify-content:flex-end;' +
    'padding:10px 14px;font:600 14px sans-serif;background:inherit;border-bottom:1px solid #8883;';
  box.appendChild(bar);

  var page = document.createElement('div');
  page.style.cssText = 'max-width:680px;margin:0 auto;padding:12px 20px 90px;';
  box.appendChild(page);

  function applyLook() {
    box.style.background = themes[themeIndex][0];
    box.style.color = themes[themeIndex][1];
    page.style.fontSize = fontSize + 'px';
  }

  function addButton(label, action) {
    var b = document.createElement('button');
    b.type = 'button';
    b.textContent = label;
    b.style.cssText = 'font:inherit;color:inherit;background:transparent;border:1px solid #8886;' +
      'border-radius:18px;padding:6px 14px;';
    b.onclick = action;
    bar.appendChild(b);
  }

  function close() {
    if (box.parentNode) box.parentNode.removeChild(box);
    document.documentElement.style.overflow = '';
  }

  addButton('A-', function () { fontSize = Math.max(13, fontSize - 2); applyLook(); });
  addButton('A+', function () { fontSize = Math.min(34, fontSize + 2); applyLook(); });
  addButton('Theme', function () { themeIndex = (themeIndex + 1) % themes.length; applyLook(); });
  addButton('Close', close);

  var heading = document.createElement('h1');
  heading.textContent = document.title || location.hostname;
  heading.style.cssText = 'font-size:1.5em;line-height:1.25;margin:.8em 0 .2em;';
  page.appendChild(heading);

  var source = document.createElement('div');
  source.textContent = location.hostname;
  source.style.cssText = 'font:14px sans-serif;opacity:.6;margin-bottom:1.4em;';
  page.appendChild(source);

  var KEEP = { P: 1, H1: 1, H2: 1, H3: 1, H4: 1, UL: 1, OL: 1, BLOCKQUOTE: 1, PRE: 1, FIGURE: 1, IMG: 1 };

  function clean(node) {
    var junk = node.querySelectorAll('script,style,iframe,form,button,svg,noscript,input,select,textarea');
    for (var j = 0; j < junk.length; j++) junk[j].parentNode.removeChild(junk[j]);
    var all = node.querySelectorAll('*');
    for (var k = 0; k < all.length; k++) {
      all[k].removeAttribute('style');
      all[k].removeAttribute('class');
      all[k].removeAttribute('id');
      all[k].removeAttribute('onclick');
      if (all[k].tagName === 'IMG') {
        var lazy = all[k].getAttribute('data-src');
        if (lazy && !all[k].getAttribute('src')) all[k].setAttribute('src', lazy);
      }
    }
    node.removeAttribute('style');
    node.removeAttribute('class');
    node.removeAttribute('id');
    return node;
  }

  if (best && bestScore >= 200) {
    var children = best.children;
    for (var c = 0; c < children.length; c++) {
      var child = children[c];
      if (!KEEP[child.tagName]) continue;
      if (child.tagName === 'P' && !(child.textContent || '').trim()) continue;
      page.appendChild(clean(child.cloneNode(true)));
    }
  } else {
    var note = document.createElement('p');
    note.textContent = 'Reader mode could not find an article on this page.';
    page.appendChild(note);
  }

  applyLook();
  document.documentElement.style.overflow = 'hidden';
  document.body.appendChild(box);
})();
"""
}
