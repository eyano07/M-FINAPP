<script setup lang="ts">
/**
 * Administration › Messagerie : serveur SMTP utilisé pour envoyer aux utilisateurs une copie e-mail de leurs
 * notifications (champ « Mail de notification » de leur profil). Le mot de passe n'est jamais relu : seule sa
 * fin est affichée. Un e-mail de test valide la configuration.
 */
definePageMeta({ roles: ['ADMIN'] })
useHead({ title: 'Messagerie' })

interface Etat {
  actif: boolean
  hote: string | null
  port: number
  securite: 'STARTTLS' | 'SSL' | 'AUCUNE'
  utilisateur: string | null
  motDePasseConfigure: boolean
  motDePasseFin: string | null
  expediteur: string | null
  urlPublique: string | null
  viaEnvironnement: boolean
  pret: boolean
  derniereErreur: string | null
  derniereErreurLe: string | null
}

const api = useApi()
const etat = ref<Etat | null>(null)
const chargement = ref(true)
const erreur = ref('')
const succes = ref('')
const action = ref<'' | 'enregistrer' | 'tester' | 'retirer'>('')
const resultatTest = ref<{ ok: boolean, message: string } | null>(null)
const voirMdp = ref(false)

const form = reactive({
  actif: false, hote: '', port: 587, securite: 'STARTTLS' as Etat['securite'],
  utilisateur: '', motDePasse: '', expediteur: '', urlPublique: '', destinataireTest: '',
})

const SECURITES = [
  { title: 'STARTTLS (port 587, le plus courant)', value: 'STARTTLS' },
  { title: 'SSL / TLS (port 465)', value: 'SSL' },
  { title: 'Aucune (port 25, réseau interne seulement)', value: 'AUCUNE' },
]

function appliquer(e: Etat) {
  etat.value = e
  form.actif = e.actif
  form.hote = e.hote ?? ''
  form.port = e.port
  form.securite = e.securite
  form.utilisateur = e.utilisateur ?? ''
  form.expediteur = e.expediteur ?? ''
  form.urlPublique = e.urlPublique ?? ''
  form.motDePasse = ''
}

async function charger() {
  chargement.value = true
  try {
    appliquer(await api<Etat>('/admin/mail'))
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les paramètres de messagerie.')
  } finally {
    chargement.value = false
  }
}
onMounted(charger)

// Suggestions de port quand on change la sécurité.
watch(() => form.securite, (s, ancien) => {
  if (!ancien) return
  if (s === 'SSL' && form.port === 587) form.port = 465
  else if (s === 'STARTTLS' && form.port === 465) form.port = 587
})

async function enregistrer() {
  erreur.value = ''
  succes.value = ''
  resultatTest.value = null
  action.value = 'enregistrer'
  try {
    appliquer(await api<Etat>('/admin/mail', {
      method: 'PUT',
      body: {
        actif: form.actif,
        hote: form.hote.trim() || null,
        port: Number(form.port) || 587,
        securite: form.securite,
        utilisateur: form.utilisateur.trim() || null,
        motDePasse: form.motDePasse || null,
        expediteur: form.expediteur.trim() || null,
        urlPublique: form.urlPublique.trim() || null,
      },
    }))
    succes.value = 'Paramètres de messagerie enregistrés. Cliquez sur « Envoyer un e-mail de test » pour les vérifier.'
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Enregistrement impossible.')
  } finally {
    action.value = ''
  }
}

async function tester() {
  erreur.value = ''
  resultatTest.value = null
  action.value = 'tester'
  try {
    resultatTest.value = await api<{ ok: boolean, message: string }>('/admin/mail/tester', {
      method: 'POST',
      body: { destinataire: form.destinataireTest.trim() || null },
    })
    appliquer(await api<Etat>('/admin/mail'))
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Le test n’a pas pu être lancé.')
  } finally {
    action.value = ''
  }
}

async function retirerMotDePasse() {
  if (!confirm('Retirer le mot de passe enregistré ?')) return
  action.value = 'retirer'
  try {
    appliquer(await api<Etat>('/admin/mail/mot-de-passe', { method: 'DELETE' }))
    succes.value = 'Mot de passe retiré.'
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de retirer le mot de passe.')
  } finally {
    action.value = ''
  }
}

const fmtDate = (d?: string | null) => (d ? new Date(d).toLocaleString('fr-FR') : '')
const modifie = computed(() => !!etat.value && (
  form.actif !== etat.value.actif || form.hote.trim() !== (etat.value.hote ?? '') || form.port !== etat.value.port
  || form.securite !== etat.value.securite || form.utilisateur.trim() !== (etat.value.utilisateur ?? '')
  || form.expediteur.trim() !== (etat.value.expediteur ?? '') || form.urlPublique.trim() !== (etat.value.urlPublique ?? '')
  || !!form.motDePasse))
</script>

<template>
  <div class="mail-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Messagerie (e-mails)</h1>
        <p class="page-sub">Serveur SMTP d'envoi des notifications par e-mail</p>
      </div>
      <v-chip v-if="etat" :color="etat.pret ? 'success' : 'grey'" variant="tonal" :prepend-icon="etat.pret ? 'mdi-check-circle' : 'mdi-circle-outline'">
        {{ etat.pret ? 'Envoi actif' : (etat.actif ? 'Serveur manquant' : 'Envoi désactivé') }}
      </v-chip>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="etat?.viaEnvironnement" type="info" variant="tonal" density="compact" class="mb-4" icon="mdi-server">
      La configuration actuelle vient du serveur (variables <code>APP_MAIL_*</code>). Enregistrer cet écran la remplace.
    </v-alert>
    <v-alert v-if="etat?.derniereErreur" type="warning" variant="tonal" class="mb-4" icon="mdi-email-alert-outline">
      Dernier envoi en échec{{ etat.derniereErreurLe ? ` (${fmtDate(etat.derniereErreurLe)})` : '' }} : {{ etat.derniereErreur }}
    </v-alert>

    <v-skeleton-loader v-if="chargement" type="card" />

    <v-card v-else class="classroom-card pa-5">
      <v-switch v-model="form.actif" color="primary" hide-details inset
        label="Envoyer une copie e-mail des notifications (aux utilisateurs qui ont renseigné un « Mail de notification »)" />

      <div class="mail-grille mt-4">
        <v-text-field v-model="form.hote" label="Serveur SMTP" placeholder="smtp.gmail.com" variant="outlined" density="comfortable"
          prepend-inner-icon="mdi-server-network-outline" hide-details="auto" autocomplete="off" />
        <v-text-field v-model.number="form.port" label="Port" type="number" variant="outlined" density="comfortable" hide-details="auto" />
        <v-select v-model="form.securite" :items="SECURITES" label="Sécurité" variant="outlined" density="comfortable" hide-details="auto" />
        <v-text-field v-model="form.utilisateur" label="Identifiant" placeholder="compte@exemple.cd" variant="outlined" density="comfortable"
          prepend-inner-icon="mdi-account-outline" hide-details="auto" autocomplete="off" />
        <div>
          <v-text-field v-model="form.motDePasse" label="Mot de passe" :type="voirMdp ? 'text' : 'password'" autocomplete="new-password"
            :placeholder="etat?.motDePasseConfigure ? `••••••••${etat.motDePasseFin ?? ''} (laisser vide pour le conserver)` : ''"
            variant="outlined" density="comfortable" hide-details="auto" prepend-inner-icon="mdi-lock-outline"
            :append-inner-icon="voirMdp ? 'mdi-eye-off-outline' : 'mdi-eye-outline'" @click:append-inner="voirMdp = !voirMdp" />
          <v-btn v-if="etat?.motDePasseConfigure && !etat.viaEnvironnement" variant="text" size="x-small" color="error" class="mt-1"
            :loading="action === 'retirer'" @click="retirerMotDePasse">Retirer le mot de passe enregistré</v-btn>
        </div>
        <v-text-field v-model="form.expediteur" label="Adresse d'expédition" placeholder="notifications@exemple.cd" variant="outlined"
          density="comfortable" prepend-inner-icon="mdi-email-outline" hide-details="auto"
          hint="Doit être une adresse que ce compte est autorisé à utiliser. Vide : l'identifiant est utilisé." persistent-hint />
        <v-text-field v-model="form.urlPublique" label="URL publique de l'application" placeholder="https://finapp.exemple.cd" variant="outlined"
          density="comfortable" prepend-inner-icon="mdi-link-variant" hide-details="auto" class="mail-large"
          hint="Sert à construire le lien « Ouvrir la notification » des e-mails. Sans elle, les e-mails n'ont pas de lien." persistent-hint />
      </div>

      <p class="mail-aide mt-4">
        Exemples : <strong>Gmail</strong> <code>smtp.gmail.com</code> · 587 · STARTTLS (mot de passe d'application) ;
        <strong>Microsoft 365</strong> <code>smtp.office365.com</code> · 587 · STARTTLS ;
        <strong>service d'envoi</strong> (Brevo, Mailjet, Amazon SES…) : valeurs fournies par le service.
        Le mot de passe est chiffré en base et n'est jamais réaffiché.
      </p>

      <v-alert v-if="resultatTest" :type="resultatTest.ok ? 'success' : 'error'" variant="tonal" density="compact" class="mt-4">
        {{ resultatTest.message }}
      </v-alert>

      <div class="mail-actions mt-4">
        <v-btn color="primary" prepend-icon="mdi-content-save-outline" :loading="action === 'enregistrer'" @click="enregistrer">Enregistrer</v-btn>
        <v-text-field v-model="form.destinataireTest" label="Envoyer le test à (facultatif)" type="email" variant="outlined" density="compact"
          hide-details class="mail-test-dest" placeholder="Vous-même par défaut" />
        <v-btn variant="tonal" prepend-icon="mdi-send-outline" :loading="action === 'tester'" :disabled="!form.hote.trim()" @click="tester">
          Envoyer un e-mail de test
        </v-btn>
      </div>
      <p v-if="modifie" class="mail-aide mt-2">Des modifications ne sont pas enregistrées : le test utilise les paramètres déjà enregistrés.</p>
    </v-card>
  </div>
</template>

<style scoped>
.mail-page { max-width: 900px; }
.mail-grille { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.mail-large { grid-column: 1 / -1; }
.mail-aide { font-size: 0.78rem; color: #4b5563; margin: 0; }
.mail-actions { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.mail-test-dest { max-width: 280px; min-width: 200px; }
@media (max-width: 700px) { .mail-grille { grid-template-columns: 1fr; } }
</style>
