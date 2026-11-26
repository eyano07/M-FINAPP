// Injecté par l'application Android dans chaque page du serveur (MainActivity.onPageFinished).
//
// Une vue web Android ne sait ni télécharger un fichier « blob: » (PDF, Excel générés par le site), ni ouvrir un
// nouvel onglet, ni imprimer. Ce script redirige ces trois gestes vers l'application (objet LogistiqueAndroid) :
// - lien <a download> ou ouverture d'un blob : le fichier est lu ici, passé en base64, enregistré dans
//   Téléchargements/MBSC Logistique puis ouvert dans l'application adaptée (lecteur PDF, tableur) ;
// - window.open('', '_blank') puis onglet.location.href = blob : même chose (aperçus PDF du site) ;
// - window.print() : impression Android (imprimante ou « Enregistrer en PDF ») de la page affichée.
(function () {
  if (window.__logistiquePasserelle) return;
  var A = window.LogistiqueAndroid;
  if (!A) return;
  window.__logistiquePasserelle = true;

  // URL.createObjectURL : on garde le Blob, car le site révoque l'URL juste après le clic (avant qu'on ait pu la lire).
  var blobs = {};
  var creer = URL.createObjectURL.bind(URL);
  var revoquer = URL.revokeObjectURL.bind(URL);
  URL.createObjectURL = function (objet) {
    var url = creer(objet);
    if (objet instanceof Blob) blobs[url] = objet;
    return url;
  };
  URL.revokeObjectURL = function (url) {
    setTimeout(function () { delete blobs[url]; revoquer(url); }, 120000);
  };

  function envoyer(blob, nom) {
    var lecteur = new FileReader();
    lecteur.onload = function () {
      var d = String(lecteur.result);
      A.enregistrer(d.substring(d.indexOf(',') + 1), nom || '', blob.type || '');
    };
    lecteur.onerror = function () { A.message('Lecture du fichier impossible.'); };
    lecteur.readAsDataURL(blob);
  }

  // Traite une adresse ouverte par le site ; vrai si elle a été prise en charge.
  function traiter(url, nom) {
    if (!url) return false;
    url = String(url);
    if (url.indexOf('blob:') === 0 || url.indexOf('data:') === 0) {
      if (blobs[url]) { envoyer(blobs[url], nom); return true; }
      fetch(url).then(function (r) { return r.blob(); })
        .then(function (b) { envoyer(b, nom); })
        .catch(function () { A.message('Fichier introuvable.'); });
      return true;
    }
    if (url === 'about:blank' || url === '') return true;
    var absolue;
    try { absolue = new URL(url, location.href); } catch (e) { return false; }
    if (absolue.origin === location.origin) { location.href = absolue.href; return true; }
    A.ouvrirLien(absolue.href);
    return true;
  }

  // <a download> et liens blob, qu'ils soient cliqués à la main ou par le code (a.click()).
  var cliquer = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.hasAttribute('download') || /^(blob|data):/.test(this.href)) {
      traiter(this.href, this.getAttribute('download'));
      return;
    }
    if (this.target === '_blank' && this.href) { traiter(this.href, null); return; }
    return cliquer.call(this);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) return;
    if (a.hasAttribute('download') || /^(blob|data):/.test(a.href) || a.target === '_blank') {
      e.preventDefault();
      e.stopPropagation();
      traiter(a.href, a.getAttribute('download'));
    }
  }, true);

  // window.open : un faux onglet dont l'adresse, une fois donnée, est traitée comme ci-dessus.
  window.open = function (url) {
    var faux = {
      closed: false,
      close: function () { this.closed = true; },
      focus: function () {},
      blur: function () {},
      document: { write: function () {}, writeln: function () {}, open: function () {}, close: function () {}, title: '' },
      location: {
        assign: function (u) { traiter(u, null); },
        replace: function (u) { traiter(u, null); },
        get href() { return 'about:blank'; },
        set href(u) { traiter(u, null); },
      },
    };
    if (url) traiter(url, null);
    return faux;
  };

  window.print = function () { A.imprimer(document.title || 'MBSC Finapp'); };

  // Couleurs du thème (couleur choisie par l'administrateur, mode clair ou nuit) : l'application en colore la barre
  // d'état et la barre de navigation d'Android. Relu à chaque changement des variables CSS ou du mode.
  if (!A.theme) return;
  var sonde = null;
  function hex(n) { n = Math.max(0, Math.min(255, Math.round(n))); return (n < 16 ? '0' : '') + n.toString(16); }
  function couleur(valeur) {
    if (!sonde) {
      sonde = document.createElement('div');
      sonde.style.cssText = 'position:absolute;width:0;height:0;visibility:hidden;pointer-events:none';
      document.body.appendChild(sonde);
    }
    sonde.style.backgroundColor = '';
    sonde.style.backgroundColor = valeur;
    var c = getComputedStyle(sonde).backgroundColor || '';
    var m = c.match(/^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)/);
    if (m) return '#' + hex(+m[1]) + hex(+m[2]) + hex(+m[3]);
    m = c.match(/^color\(srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)/);   // color-mix() calculé par Chrome
    if (m) return '#' + hex(m[1] * 255) + hex(m[2] * 255) + hex(m[3] * 255);
    return null;
  }
  var envoye = '';
  function signaler() {
    if (!document.body) return;
    var sombre = document.documentElement.getAttribute('data-theme') === 'dark';
    var etat = couleur(sombre ? 'var(--canvas)' : 'var(--color-primary-dark)');
    var nav = couleur('var(--canvas)');
    if (!etat || !nav) return;
    var cle = etat + nav + sombre;
    if (cle === envoye) return;
    envoye = cle;
    A.theme(etat, nav, sombre);
  }
  var attente = null;
  function plusTard() { clearTimeout(attente); attente = setTimeout(signaler, 120); }
  new MutationObserver(plusTard).observe(document.documentElement, { attributes: true, attributeFilter: ['style', 'data-theme', 'class'] });
  if (window.matchMedia) {
    var q = window.matchMedia('(prefers-color-scheme: dark)');
    if (q.addEventListener) q.addEventListener('change', plusTard);
  }
  plusTard();
  setTimeout(signaler, 1500);   // couleur venue du serveur après le premier affichage
})();
