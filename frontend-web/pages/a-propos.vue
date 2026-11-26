<script setup lang="ts">
import QRCode from 'qrcode'
import { useParametresStore } from '~/stores/parametres'
import { estApplicationMobile } from '~/utils/applicationMobile'

// Accessible à tout compte connecté, comme /profil et /aide.
definePageMeta({})

const parametres = useParametresStore()
const version = useRuntimeConfig().public.appVersion as string

const MODULES = [
  { icone: 'mdi-receipt-text-outline', titre: 'Notes de frais', texte: 'Circuit de validation : émetteur, DFIN, DA, puis paiement par la caisse.' },
  { icone: 'mdi-cash-register', titre: 'Trésorerie', texte: 'Caisse, banque et mobile money, journaux et rapport de caisse en USD et FC.' },
  { icone: 'mdi-book-open-variant', titre: 'Comptabilité SYSCOHADA', texte: 'Pièces, grand livre, balance, bilan, compte de résultat, TVA, clôture, liasse OHADA.' },
  { icone: 'mdi-chart-box-outline', titre: 'Budgets', texte: 'Budgets par période et par service, contrôle budgétaire des dépenses.' },
  { icone: 'mdi-cart-outline', titre: 'Ventes et restaurant', texte: 'Ventes, clients, carte, cuisine, bar, stocks et analyses des ventes.' },
  { icone: 'mdi-warehouse', titre: 'Logistique et patrimoine', texte: 'Articles, entrepôts, fournisseurs, immobilisations et amortissements.' },
  { icone: 'mdi-account-group-outline', titre: 'Ressources humaines', texte: 'Employés, présences, paie RDC, missions.' },
  { icone: 'mdi-cellphone', titre: 'Application mobile', texte: 'Validation des notes, consultation et notifications sur Android.' },
]

const EQUIPE = [
  { nom: 'Pascal Tshingila', fonction: 'Concepteur', icone: 'mdi-lightbulb-on-outline' },
  { nom: 'Bruno Kalunga', fonction: 'Expert financier et fiscal', icone: 'mdi-scale-balance' },
  { nom: 'Claude Mande', fonction: 'Expert comptable', icone: 'mdi-calculator-variant-outline' },
]

// Application Android : APK servi par nginx (/telechargements/, déposé à côté de version.json par
// mobile-android/outils/publier-apk.sh). Pas de carte si aucun APK n'est publié sur ce serveur ; rien dans l'application.
interface VersionApk { version: string; taille?: number; date?: string; fichier?: string }
const applicationMobile = estApplicationMobile()
const apk = ref<VersionApk | null>(null)
// MBSC Logistique (26/11/2026) : scanner des QR des biens (mobile-logistique/outils/publier-apk.sh, version-logistique.json).
const apkLogistique = ref<VersionApk | null>(null)
async function lireVersion(fichier: string) {
  try {
    const r = await fetch(`/telechargements/${fichier}`, { cache: 'no-store' })
    return r.ok ? await r.json() as VersionApk : null
  } catch { return null /* aucun APK publié */ }
}
onMounted(async () => {
  parametres.charger()   // adresse du serveur à jour (modifiée par l'administrateur)
  if (applicationMobile) return
  ;[apk.value, apkLogistique.value] = await Promise.all([lireVersion('version.json'), lireVersion('version-logistique.json')])
})
// Adresse du serveur saisie par l'administrateur (Administration › Paramètres) et son QR : scanné au premier lancement
// des applications (Finapp ≥ 2.13, Logistique), sans rien saisir.
const adresseServeur = computed(() => parametres.parametres.adresseServeur || '')
const qrServeur = ref('')
watch(adresseServeur, async a => {
  qrServeur.value = a ? await QRCode.toDataURL(a, { margin: 1, width: 220, errorCorrectionLevel: 'M' }) : ''
}, { immediate: true })
const lienApkLogistique = computed(() => `/telechargements/${apkLogistique.value?.fichier || 'MBSC-Logistique.apk'}`)
const lienApk = computed(() => `/telechargements/${apk.value?.fichier || 'MBSC-Finapp.apk'}`)
const tailleApk = computed(() => apk.value?.taille ? `${(apk.value.taille / 1048576).toFixed(1).replace('.', ',')} Mo` : '')
function datePublication(v: VersionApk | null) {
  if (!v?.date) return ''
  const d = new Date(v.date)
  return isNaN(d.getTime()) ? '' : d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })
}
const dateApk = computed(() => datePublication(apk.value))
const detailLogistique = computed(() => [
  apkLogistique.value?.taille ? `${(apkLogistique.value.taille / 1048576).toFixed(1).replace('.', ',')} Mo` : '',
  datePublication(apkLogistique.value) ? `publiée le ${datePublication(apkLogistique.value)}` : '',
].filter(Boolean).join(' · '))

const TELEPHONE = '+243 86 26 93 422'
const WHATSAPP = '+243 82 30 88 383'
const chiffres = (n: string) => n.replace(/[^\d+]/g, '')
</script>

<template>
  <div class="apropos">
    <section class="apropos__hero">
      <div class="apropos__logo"><LogoMfinapp /></div>
      <h1 class="apropos__titre">M-FINAPP</h1>
      <p class="apropos__accroche">Progiciel de gestion financière et comptable</p>
      <span class="apropos__version">Version {{ version }}</span>
    </section>

    <section class="apropos__carte">
      <h2 class="apropos__h2">Le système</h2>
      <p class="apropos__texte">
        M-FINAPP réunit dans un même outil la gestion financière, comptable et opérationnelle de l'entreprise, selon le
        référentiel comptable SYSCOHADA révisé. Chaque dépense suit un circuit de validation tracé, de la note de frais
        jusqu'au paiement et à son écriture comptable ; chaque vente alimente la trésorerie, les stocks et la comptabilité
        analytique. Les droits de chaque rôle et la séparation des tâches protègent les fonds, et un journal d'audit garde
        la trace de chaque action.
      </p>
      <div class="apropos__modules">
        <div v-for="m in MODULES" :key="m.titre" class="apropos__module">
          <span class="apropos__module-icone"><v-icon :icon="m.icone" size="20" /></span>
          <div>
            <p class="apropos__module-titre">{{ m.titre }}</p>
            <p class="apropos__module-texte">{{ m.texte }}</p>
          </div>
        </div>
      </div>
    </section>

    <section class="apropos__carte">
      <h2 class="apropos__h2">L'équipe</h2>
      <div class="apropos__equipe">
        <div v-for="p in EQUIPE" :key="p.nom" class="apropos__personne">
          <span class="apropos__avatar"><v-icon :icon="p.icone" size="22" /></span>
          <p class="apropos__nom">{{ p.nom }}</p>
          <p class="apropos__fonction">{{ p.fonction }}</p>
        </div>
      </div>
    </section>

    <section v-if="apk || apkLogistique" class="apropos__carte">
      <h2 class="apropos__h2">Applications Android</h2>
      <div v-if="apk" class="apropos__apk">
        <span class="apropos__apk-icone"><v-icon icon="mdi-android" size="28" /></span>
        <div class="apropos__apk-textes">
          <p class="apropos__apk-titre">MBSC Finapp pour Android · version {{ apk.version }}</p>
          <p class="apropos__apk-detail">
            Validation des notes de frais, tableau de bord, notifications et widgets de l'écran d'accueil.
            <template v-if="tailleApk || dateApk"><br>{{ [tailleApk, dateApk ? `publiée le ${dateApk}` : ''].filter(Boolean).join(' · ') }}</template>
          </p>
        </div>
        <v-btn :href="lienApk" download color="primary" variant="flat" rounded="lg" prepend-icon="mdi-download">
          Télécharger l'APK
        </v-btn>
      </div>
      <div v-if="apkLogistique" class="apropos__apk" :class="{ 'mt-3': apk }">
        <span class="apropos__apk-icone"><v-icon icon="mdi-qrcode-scan" size="26" /></span>
        <div class="apropos__apk-textes">
          <p class="apropos__apk-titre">MBSC Logistique pour Android · version {{ apkLogistique.version }}</p>
          <p class="apropos__apk-detail">
            Logistique, approvisionnement et patrimoine ; scan du QR d'un bien pour ouvrir sa fiche.
            <template v-if="detailLogistique"><br>{{ detailLogistique }}</template>
          </p>
        </div>
        <v-btn :href="lienApkLogistique" download color="primary" variant="flat" rounded="lg" prepend-icon="mdi-download">
          Télécharger l'APK
        </v-btn>
      </div>
      <div v-if="!adresseServeur" class="apropos__serveur">
        <v-icon icon="mdi-qrcode-remove" size="40" color="medium-emphasis" />
        <p>
          <strong>QR du serveur non disponible</strong><br>
          L'administrateur doit saisir l'adresse du serveur (URL ou IP joignable par les téléphones) dans
          Administration › Paramètres, rubrique « Applications Android ».
        </p>
      </div>
      <div v-else-if="qrServeur" class="apropos__serveur">
        <img :src="qrServeur" alt="QR de l'adresse du serveur" width="110" height="110">
        <p>
          <strong>Adresse de ce serveur</strong><br>
          <code>{{ adresseServeur }}</code><br>
          Au premier lancement de MBSC Finapp ou de MBSC Logistique, touchez « Scanner le QR du serveur » et visez
          ce QR : rien à saisir.
        </p>
      </div>
      <p class="apropos__apk-aide">
        Ouvrez ce lien depuis le téléphone (connecté au réseau de l'entreprise), puis autorisez l'installation
        d'applications de cette source si Android le demande. Une nouvelle version s'installe par-dessus l'ancienne,
        sans perdre la connexion. Au premier lancement, scannez le QR ci-dessus ou saisissez l'adresse de ce serveur.
      </p>
    </section>

    <section class="apropos__carte">
      <h2 class="apropos__h2">Nous contacter</h2>
      <div class="apropos__contacts">
        <a :href="`tel:${chiffres(TELEPHONE)}`" class="apropos__contact">
          <span class="apropos__contact-icone"><v-icon icon="mdi-phone-outline" size="22" /></span>
          <span class="apropos__contact-textes">
            <span class="apropos__contact-libelle">Téléphone</span>
            <span class="apropos__contact-valeur">{{ TELEPHONE }}</span>
          </span>
        </a>
        <a :href="`https://wa.me/${chiffres(WHATSAPP).replace('+', '')}`" target="_blank" rel="noopener" class="apropos__contact">
          <span class="apropos__contact-icone apropos__contact-icone--wa"><v-icon icon="mdi-whatsapp" size="22" /></span>
          <span class="apropos__contact-textes">
            <span class="apropos__contact-libelle">WhatsApp</span>
            <span class="apropos__contact-valeur">{{ WHATSAPP }}</span>
          </span>
        </a>
      </div>
    </section>

    <p class="apropos__pied">&copy; {{ new Date().getFullYear() }} M-FINAPP · {{ parametres.parametres.nom }}</p>
  </div>
</template>

<style scoped>
.apropos { max-width: 860px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }
.apropos__hero {
  text-align: center; padding: 28px 16px 22px; border-radius: 22px; color: #fff;
  background: radial-gradient(120% 120% at 0% 0%, rgba(255,255,255,0.18), transparent 45%), radial-gradient(80% 120% at 100% 100%, rgba(99,102,241,0.55), transparent 60%), linear-gradient(135deg, var(--color-primary) 0%, color-mix(in srgb, var(--color-primary) 60%, #0f766e) 55%, color-mix(in srgb, var(--color-primary) 35%, #1e3a8a) 100%);
}
.apropos__logo {
  width: 64px; height: 64px; margin: 0 auto 12px; border-radius: 18px; display: grid; place-items: center;
  overflow: hidden; box-shadow: 0 6px 18px rgba(0, 0, 0, 0.22);
}
.apropos__logo { padding: 10px; background: rgba(255, 255, 255, 0.14); }
.apropos__titre { margin: 0; font-size: 28px; font-weight: 800; letter-spacing: 0.02em; }
.apropos__accroche { margin: 4px 0 12px; opacity: 0.88; }
.apropos__version { display: inline-block; padding: 4px 12px; border-radius: 999px; font-size: 12.5px; font-weight: 600; background: rgba(255, 255, 255, 0.16); }
.apropos__carte { background: var(--surface); border: 1px solid var(--hairline); border-radius: 18px; padding: 18px; }
.apropos__h2 { font-size: 17px; font-weight: 700; margin: 0 0 10px; color: var(--ink); }
.apropos__texte { margin: 0 0 14px; line-height: 1.6; color: var(--ink-2); font-size: 14.5px; }
.apropos__modules { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 10px; }
.apropos__module { display: flex; gap: 12px; padding: 12px; border-radius: 14px; background: var(--surface-2); }
.apropos__module-icone {
  flex: 0 0 auto; width: 38px; height: 38px; border-radius: 12px; display: grid; place-items: center;
  background: color-mix(in srgb, var(--color-primary) 14%, transparent); color: var(--color-primary);
}
.apropos__module-titre { margin: 0; font-weight: 650; font-size: 14px; color: var(--ink); }
.apropos__module-texte { margin: 2px 0 0; font-size: 13px; color: var(--ink-soft); line-height: 1.4; }
.apropos__equipe { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; }
.apropos__personne { text-align: center; padding: 16px 12px; border-radius: 14px; background: var(--surface-2); }
.apropos__avatar {
  width: 48px; height: 48px; margin: 0 auto 8px; border-radius: 50%; display: grid; place-items: center;
  background: color-mix(in srgb, var(--color-primary) 14%, transparent); color: var(--color-primary);
}
.apropos__nom { margin: 0; font-weight: 700; color: var(--ink); }
.apropos__fonction { margin: 2px 0 0; font-size: 13px; color: var(--ink-soft); }
.apropos__contacts { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; }
.apropos__contact {
  display: flex; align-items: center; gap: 12px; padding: 12px 14px; border-radius: 14px; text-decoration: none;
  color: inherit; background: var(--surface-2); border: 1px solid var(--hairline);
}
.apropos__contact-icone {
  width: 42px; height: 42px; border-radius: 12px; display: grid; place-items: center;
  background: color-mix(in srgb, var(--color-primary) 14%, transparent); color: var(--color-primary);
}
.apropos__contact-icone--wa { background: color-mix(in srgb, #25d366 16%, transparent); color: #1da851; }
.apropos__contact-textes { display: flex; flex-direction: column; }
.apropos__contact-libelle { font-size: 12.5px; color: var(--ink-soft); }
.apropos__contact-valeur { font-weight: 650; color: var(--ink); font-variant-numeric: tabular-nums; }
.apropos__apk { display: flex; flex-wrap: wrap; align-items: center; gap: 14px; padding: 14px; border-radius: 14px; background: var(--surface-2); }
.apropos__apk-icone {
  flex: 0 0 auto; width: 48px; height: 48px; border-radius: 14px; display: grid; place-items: center;
  background: color-mix(in srgb, #3ddc84 18%, transparent); color: #1f9e5c;
}
.apropos__apk-textes { flex: 1 1 220px; min-width: 0; }
.apropos__apk-titre { margin: 0; font-weight: 700; color: var(--ink); }
.apropos__apk-detail { margin: 2px 0 0; font-size: 13px; color: var(--ink-soft); line-height: 1.45; }
.apropos__serveur { display: flex; align-items: center; gap: 14px; margin-top: 12px; padding: 14px; border-radius: 14px; background: var(--surface-2); }
.apropos__serveur img { border-radius: 8px; background: #fff; flex: 0 0 auto; }
.apropos__serveur p { margin: 0; font-size: 13px; color: var(--ink-soft); line-height: 1.5; word-break: break-all; }
.apropos__apk-aide { margin: 10px 2px 0; font-size: 12.5px; color: var(--ink-faint); line-height: 1.5; }
.apropos__pied { text-align: center; color: var(--ink-faint); font-size: 12.5px; margin: 4px 0 8px; }
</style>
