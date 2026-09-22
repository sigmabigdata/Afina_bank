import cadesplugin from './vendor/crypto-pro-cadesplugin.js';

console.log('[sign.js] module loaded');

document.addEventListener('DOMContentLoaded', function () {
    console.log('[sign.js] DOMContentLoaded');

    var toast = document.createElement('div');
    toast.id = 'status-toast';
    document.body.appendChild(toast);

    function show(msg, kind) {
        console.log('[sign.js]', kind || 'info', msg);
        toast.textContent = msg;
        toast.className = kind || '';
        toast.style.display = 'block';
    }

    document.addEventListener('click', function (ev) {
        var btn = ev.target.closest('.btn-sign');
        if (!btn) return;
        ev.preventDefault();
        var id = btn.getAttribute('data-id');
        console.log('[sign.js] click, id =', id);
        if (id) signDocument(id);
    });

    async function signDocument(id) {
        try {
            show('Подготовка подписи...', 'info');

            var respFile = await fetch('/documents/' + id + '/view', { credentials: 'same-origin' });
            if (!respFile.ok) throw new Error('Файл не получен: HTTP ' + respFile.status);
            var buf = await respFile.arrayBuffer();
            var contentBase64 = arrayBufferToBase64(buf);
            console.log('[sign.js] file loaded, bytes =', buf.byteLength);

            show('Ожидание плагина...', 'info');
            var api = await cadesplugin();

            show('Поиск сертификатов...', 'info');
            var certs = await api.getCertsList();
            console.log('[sign.js] certs =', certs && certs.length);
            if (!certs || certs.length === 0) {
                throw new Error('В хранилище «Личные» нет сертификатов');
            }
            var cert = certs[0];
            console.log('[sign.js] cert thumbprint =', cert.thumbprint);

            show('Подписание...', 'info');
            var signatureRaw = await api.signBase64(cert.thumbprint, contentBase64);
            var signatureB64 = sanitizeBase64(signatureRaw);
            console.log('[sign.js] signature raw length =', signatureRaw.length,
                        ', cleaned length =', signatureB64.length);

            show('Проверка подписи на сервере...', 'info');
            var headers = { 'Content-Type': 'application/json' };
            var csrf = getCsrf();
            if (csrf) headers[getCsrfHeader() || 'X-CSRF-TOKEN'] = csrf;

            var resp = await fetch('/api/sign/accept', {
                method: 'POST',
                credentials: 'same-origin',
                headers: headers,
                body: JSON.stringify({ documentId: id, signatureBase64: signatureB64 })
            });
            var data = await resp.json().catch(function () { return {}; });
            if (resp.ok && data.valid) {
                show('Документ подписан.\n' + (data.signersInfo || ''), 'ok');
                setTimeout(function () { location.reload(); }, 1500);
            } else {
                show('Подпись не принята: ' + (data.error || resp.statusText), 'err');
            }
        } catch (e) {
            console.error('[sign.js] error', e);
            show('Ошибка: ' + (e && e.message ? e.message : e), 'err');
        }
    }

    // Очистка подписи от всего, что не является стандартным Base64.
    function sanitizeBase64(s) {
        if (!s) return s;
        return String(s)
            .replace(/-----BEGIN[^-]*-----/g, '')
            .replace(/-----END[^-]*-----/g, '')
            .replace(/[^A-Za-z0-9+/=]/g, '');
    }

    function arrayBufferToBase64(buf) {
        var binary = '', bytes = new Uint8Array(buf), chunk = 0x8000;
        for (var i = 0; i < bytes.length; i += chunk)
            binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
        return btoa(binary);
    }
    function getCsrf() { var m = document.querySelector('meta[name="_csrf"]'); return m ? m.getAttribute('content') : null; }
    function getCsrfHeader() { var m = document.querySelector('meta[name="_csrf_header"]'); return m ? m.getAttribute('content') : null; }
});
