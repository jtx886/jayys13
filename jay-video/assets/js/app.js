/* Jay影视 - 前端交互（原生JS，无任何外部库） */
(function () {
  'use strict';

  /* ---------- 图片淡入 ---------- */
  function bindImgFade(root) {
    (root || document).querySelectorAll('img[data-fade]').forEach(function (img) {
      if (img.dataset.fdB) return;
      img.dataset.fdB = '1';
      if (img.complete && img.naturalWidth > 0) { img.classList.add('loaded'); }
      img.addEventListener('load', function () { img.classList.add('loaded'); });
      img.addEventListener('error', function () { img.classList.add('loaded'); img.style.opacity = .35; });
    });
  }
  window.bindImgFade = bindImgFade;
  document.addEventListener('DOMContentLoaded', function () { bindImgFade(); });

  /* ---------- Toast ---------- */
  var toastWrap = null;
  window.showToast = function (msg, type) {
    if (!toastWrap) {
      toastWrap = document.createElement('div');
      toastWrap.className = 'toast-wrap';
      document.body.appendChild(toastWrap);
    }
    var t = document.createElement('div');
    t.className = 'toast ' + (type || 'info');
    var ic = document.createElement('i');
    ic.className = 'ic ' + (type === 'success' ? 'ic-check' : type === 'error' ? 'ic-info' : 'ic-horn');
    t.appendChild(ic);
    var sp = document.createElement('span');
    sp.textContent = msg;
    t.appendChild(sp);
    toastWrap.appendChild(t);
    setTimeout(function () {
      t.classList.add('out');
      setTimeout(function () { t.remove(); }, 320);
    }, 2600);
  };

  /* Flash 消息 */
  document.addEventListener('DOMContentLoaded', function () {
    var el = document.getElementById('flash-data');
    if (el && el.dataset.msg) { window.showToast(el.dataset.msg, el.dataset.type || 'info'); }
  });

  /* ---------- 导航 ---------- */
  document.addEventListener('click', function (e) {
    var toggle = e.target.closest('.nav-toggle');
    if (toggle) {
      var menu = document.getElementById('nav-menu');
      if (menu) menu.classList.toggle('show');
      return;
    }
    /* 用户下拉 */
    var uBtn = e.target.closest('.nav-user');
    var dd = document.querySelector('.user-dropdown');
    if (uBtn && dd) {
      if (!e.target.closest('.user-dropdown')) { dd.classList.toggle('show'); return; }
    }
    if (dd && !e.target.closest('.nav-user') && !e.target.closest('.user-dropdown')) {
      dd.classList.remove('show');
    }
    /* 下拉/弹窗关闭按钮 */
    var closer = e.target.closest('[data-close]');
    if (closer) {
      var ov = closer.closest('.overlay');
      if (ov) ov.classList.remove('show');
    }
    /* data-modal-open */
    var opener = e.target.closest('[data-modal-open]');
    if (opener) {
      var m = document.getElementById(opener.getAttribute('data-modal-open'));
      if (m) m.classList.add('show');
    }
  });

  /* ---------- 表单确认 ---------- */
  document.addEventListener('submit', function (e) {
    var f = e.target;
    if (f.dataset.confirm && !window.confirm(f.dataset.confirm)) { e.preventDefault(); }
  });

  /* ---------- Hero 轮播 ---------- */
  document.addEventListener('DOMContentLoaded', function () {
    var hero = document.getElementById('hero');
    if (!hero) return;
    var slides = hero.querySelectorAll('.hero-slide');
    var dots = hero.querySelectorAll('.hero-dot');
    if (slides.length === 0) return;
    var cur = 0, timer = null;
    function go(i) {
      cur = (i + slides.length) % slides.length;
      slides.forEach(function (s, n) { s.classList.toggle('active', n === cur); });
      dots.forEach(function (d, n) { d.classList.toggle('active', n === cur); });
    }
    function play() { timer = setInterval(function () { go(cur + 1); }, 6000); }
    function stop() { if (timer) clearInterval(timer); }
    dots.forEach(function (d, n) { d.addEventListener('click', function () { stop(); go(n); play(); }); });
    var prev = hero.querySelector('.hero-prev'), next = hero.querySelector('.hero-next');
    if (prev) prev.addEventListener('click', function () { stop(); go(cur - 1); play(); });
    if (next) next.addEventListener('click', function () { stop(); go(cur + 1); play(); });
    hero.addEventListener('mouseenter', stop);
    hero.addEventListener('mouseleave', play);
    play();
  });

  /* ---------- 公告弹窗 ---------- */
  document.addEventListener('DOMContentLoaded', function () {
    var nm = document.getElementById('notice-overlay');
    if (!nm) return;
    setTimeout(function () { nm.classList.add('show'); }, 500);
    var okBtn = document.getElementById('notice-ok');
    if (okBtn) {
      okBtn.addEventListener('click', function () {
        var chk = document.getElementById('notice-no-show');
        var ver = nm.dataset.version || '';
        if (chk && chk.checked) {
          var d = new Date(); d.setTime(d.getTime() + 365 * 864e5);
          document.cookie = 'jay_notice_ack=' + encodeURIComponent(ver) + ';expires=' + d.toUTCString() + ';path=/';
        } else {
          document.cookie = 'jay_notice_ack=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/';
        }
        nm.classList.remove('show');
      });
    }
  });

  /* ---------- 发送验证码 ---------- */
  window.sendVerifyCode = function (btn) {
    var email = (document.getElementById('reg-email') || {}).value || '';
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) { showToast('请输入正确的邮箱地址', 'error'); return; }
    btn.disabled = true;
    var old = btn.textContent;
    btn.innerHTML = '<i class="ic ic-spin"></i> 发送中';
    fetch('api.php?action=send_code', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'email=' + encodeURIComponent(email) + '&csrf=' + encodeURIComponent(window.JAY_CSRF || '')
    }).then(function (r) { return r.json(); }).then(function (d) {
      showToast(d.msg || (d.ok ? '发送成功' : '发送失败'), d.ok ? 'success' : 'error');
      if (d.ok) {
        var s = 60;
        btn.textContent = s + 's后重发';
        var t = setInterval(function () {
          s--;
          if (s <= 0) { clearInterval(t); btn.disabled = false; btn.textContent = old; }
          else { btn.textContent = s + 's后重发'; }
        }, 1000);
      } else { btn.disabled = false; btn.textContent = old; }
    }).catch(function () { btn.disabled = false; btn.textContent = old; showToast('网络异常，请重试', 'error'); });
  };

  /* ---------- 点赞 ---------- */
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('.like-btn');
    if (!btn) return;
    e.preventDefault();
    var fid = btn.dataset.id;
    fetch('api.php?action=like', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'feedback_id=' + encodeURIComponent(fid) + '&csrf=' + encodeURIComponent(window.JAY_CSRF || '')
    }).then(function (r) { return r.json(); }).then(function (d) {
      if (d.ok) {
        var cnt = btn.querySelector('span');
        if (cnt) cnt.textContent = d.count;
        btn.classList.toggle('liked', d.liked);
        var ic = btn.querySelector('.ic');
        if (ic && d.liked) { ic.classList.remove('on'); void ic.offsetWidth; ic.classList.add('on'); }
      } else { showToast(d.msg || '操作失败', 'error'); }
    }).catch(function () { showToast('网络异常', 'error'); });
  });

  /* ---------- 展开回复 ---------- */
  document.addEventListener('click', function (e) {
    var tg = e.target.closest('.reply-toggle');
    if (!tg) return;
    var box = tg.closest('.reply-list');
    if (!box) return;
    var collapsed = box.querySelectorAll('.reply-item.reply-hidden');
    if (collapsed.length) {
      collapsed.forEach(function (r) { r.style.display = 'flex'; r.classList.add('shown'); r.classList.remove('reply-hidden'); });
      var total = parseInt(tg.dataset.total || '0', 10);
      tg.innerHTML = '<i class="ic ic-arrow-l" style="transform:rotate(90deg)"></i> 收起回复';
      tg.dataset.mode = 'open';
    } else {
      var items = box.querySelectorAll('.reply-item');
      items.forEach(function (r, i) { if (i >= 3) { r.style.display = 'none'; r.classList.add('reply-hidden'); r.classList.remove('shown'); } });
      tg.innerHTML = '<i class="ic ic-arrow-l" style="transform:rotate(-90deg)"></i> 展开全部' + (tg.dataset.total || items.length) + '条回复';
      tg.dataset.mode = 'closed';
    }
  });
  window.initReplyCollapse = function () {
    document.querySelectorAll('.reply-list').forEach(function (box) {
      var items = box.querySelectorAll('.reply-item');
      var tg = box.querySelector('.reply-toggle');
      if (items.length > 3 && tg) {
        items.forEach(function (r, i) { if (i >= 3) { r.style.display = 'none'; r.classList.add('reply-hidden'); } });
        tg.innerHTML = '<i class="ic ic-arrow-l" style="transform:rotate(-90deg)"></i> 展开全部' + (tg.dataset.total || items.length) + '条回复';
        tg.dataset.mode = 'closed';
        tg.style.display = 'flex';
      } else if (tg) { tg.style.display = 'none'; }
    });
  };
  document.addEventListener('DOMContentLoaded', window.initReplyCollapse);

  /* ---------- radio-card 选中态 ---------- */
  document.addEventListener('change', function (e) {
    if (e.target.closest('.radio-card')) {
      document.querySelectorAll('.radio-card').forEach(function (c) {
        var input = c.querySelector('input');
        if (input) c.classList.toggle('checked', input.checked);
      });
    }
  });

  /* ---------- 主题色实时预览 ---------- */
  window.previewTheme = function (input) {
    document.documentElement.style.setProperty('--primary', input.value);
  };
})();
