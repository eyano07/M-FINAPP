<script setup lang="ts">
/**
 * Administration › Journal d'audit : lecture et analyse du fichier d'audit (qui a fait quoi, quand, avec quel
 * résultat), synthèse chiffrée, export CSV et agent IA qui répond en langage clair aux questions de l'administrateur.
 */
definePageMeta({ roles: ['ADMIN'] })
useHead({ title: "Journal d'audit" })

interface Evenement {
  horodatage: string, type: string, utilisateurId: number | null, email: string | null, roles: string[] | null, ip: string | null,
  module: string | null, operation: string | null, ressourceId: string | null, methode: string | null, chemin: string | null,
  requete: string | null, statut: number | null, reussi: boolean, dureeMs: number | null, detail: string | null,
  agent: string | null, terminal: string, systeme: string
}
interface Compte { cle: string, total: number, echecs: number }
interface Jour { jour: string, total: number, echecs: number }
interface Synthese {
  total: number, echecs: number, connexionsRefusees: number, utilisateursActifs: number, tronque: boolean,
  parUtilisateur: Compte[], parModule: Compte[], parOperation: Compte[], parTerminal: Compte[], parSysteme: Compte[], parJour: Jour[]
}
interface PageEv { total: number, page: number, taille: number, tronque: boolean, evenements: Evenement[] }
interface ReponseIa {
  reponse: string, pointsCles: string[], alertes: string[], limites: string | null,
  evenementsPeriode: number, evenementsTransmis: number, echantillon: boolean
}

const api = useApi()
const iso = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10)
const aujourdhui = new Date()
const il = (n: number) => { const d = new Date(); d.setDate(d.getDate() - n); return iso(d) }

const filtre = reactive({ du: il(6), au: iso(aujourdhui), utilisateur: '', module: '', operation: '', resultat: '', terminal: '', systeme: '', q: '' })
const page = ref(0)
const taille = 50
const synthese = ref<Synthese | null>(null)
const resultat = ref<PageEv | null>(null)
const chargement = ref(true)
const erreur = ref('')
const detail = ref<Evenement | null>(null)

function params(extra: Record<string, string | number> = {}) {
  const p: Record<string, string | number> = { du: filtre.du, au: filtre.au, ...extra }
  for (const k of ['utilisateur', 'module', 'operation', 'resultat', 'terminal', 'systeme', 'q'] as const) if (filtre[k]) p[k] = filtre[k]
  return p
}
const requete = (extra: Record<string, string | number> = {}) =>
  Object.entries(params(extra)).map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`).join('&')

async function charger(resetPage = true) {
  if (resetPage) page.value = 0
  chargement.value = true
  erreur.value = ''
  try {
    const [s, p] = await Promise.all([
      api<Synthese>(`/admin/audit/synthese?du=${filtre.du}&au=${filtre.au}`),
      api<PageEv>(`/admin/audit/evenements?${requete({ page: page.value, taille })}`),
    ])
    synthese.value = s
    resultat.value = p
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de lire le journal d’audit.')
  } finally {
    chargement.value = false
  }
}
onMounted(() => charger())

function periode(jours: number) {
  filtre.du = il(jours)
  filtre.au = iso(new Date())
  charger()
}
function reinitialiser() {
  Object.assign(filtre, { utilisateur: '', module: '', operation: '', resultat: '', terminal: '', systeme: '', q: '' })
  charger()
}
async function aller(delta: number) {
  page.value = Math.max(0, page.value + delta)
  await charger(false)
}
const nbPages = computed(() => (resultat.value ? Math.max(1, Math.ceil(resultat.value.total / taille)) : 1))

async function exporter() {
  try {
    await telechargerFichier(api, `/admin/audit/export?${requete()}`, 'journal-audit.csv')
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Export impossible.')
  }
}

const fmtDate = (d: string) => new Date(d).toLocaleString('fr-FR')
const max = (l: { total: number }[]) => Math.max(1, ...l.map(x => x.total))
const pct = (n: number, m: number) => `${Math.round((n / m) * 100)}%`
const modules = computed(() => synthese.value?.parModule.map(m => m.cle) ?? [])
const operations = computed(() => synthese.value?.parOperation.map(m => m.cle) ?? [])
const utilisateurs = computed(() => synthese.value?.parUtilisateur.map(m => m.cle) ?? [])
const terminaux = computed(() => synthese.value?.parTerminal.map(m => m.cle) ?? [])
const systemes = computed(() => synthese.value?.parSysteme.map(m => m.cle) ?? [])
const iconeTerminal = (t?: string | null) =>
  t === 'Mobile' ? 'mdi-cellphone' : t === 'Tablette' ? 'mdi-tablet' : t === 'Ordinateur' ? 'mdi-monitor'
    : t === 'Application' ? 'mdi-application-cog-outline' : 'mdi-help-circle-outline'
/** Système à afficher à côté du terminal : rien quand il est inconnu (ou quand le terminal l'est). */
const systemeConnu = (e: { terminal: string, systeme: string }) => e.terminal !== 'Inconnu' && !!e.systeme && e.systeme !== 'Inconnu'
const couleurOperation = (op?: string | null) =>
  op === 'SUPPRIMER' || op === 'ANNULER' ? 'error' : op === 'CONNEXION' || op === 'DECONNEXION' ? 'info' : op === 'CREER' ? 'success' : 'primary'

// ── Agent IA ──────────────────────────────────────────────────────────────
const iaOuvert = ref(false)
const question = ref('')
const iaChargement = ref(false)
const iaErreur = ref('')
const iaReponse = ref<ReponseIa | null>(null)
const SUGGESTIONS = [
  'Fais-moi un résumé de l’activité de la période.',
  'Y a-t-il des comportements inhabituels à vérifier ?',
  'Quelles actions ont échoué, et pourquoi à ton avis ?',
  'Qui a supprimé ou annulé des données ?',
  'Quels utilisateurs sont les plus actifs ?',
  'Y a-t-il eu des connexions refusées ?',
  'Qui se connecte depuis un mobile ou une tablette ?',
]
async function demander(q?: string) {
  if (q) question.value = q
  if (!question.value.trim()) return
  iaChargement.value = true
  iaErreur.value = ''
  iaReponse.value = null
  try {
    iaReponse.value = await api<ReponseIa>('/admin/audit/interroger', {
      method: 'POST', body: { question: question.value.trim(), du: filtre.du, au: filtre.au },
    })
  } catch (e) {
    iaErreur.value = messageErreurApi(e, 'L’agent IA n’a pas pu répondre.')
  } finally {
    iaChargement.value = false
  }
}
</script>

<template>
  <div class="au-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Journal d'audit</h1>
        <p class="page-sub">Qui a fait quoi, quand et avec quel résultat — la simple consultation de données n'est pas enregistrée</p>
      </div>
      <div class="au-head-actions">
        <v-btn color="primary" prepend-icon="mdi-robot-outline" @click="iaOuvert = true">Interroger l'IA</v-btn>
        <v-btn variant="tonal" prepend-icon="mdi-file-delimited-outline" @click="exporter">Exporter (CSV)</v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <!-- Filtres -->
    <v-card class="classroom-card pa-4 mb-4">
      <div class="au-periodes">
        <v-chip size="small" variant="tonal" @click="periode(0)">Aujourd'hui</v-chip>
        <v-chip size="small" variant="tonal" @click="periode(6)">7 jours</v-chip>
        <v-chip size="small" variant="tonal" @click="periode(29)">30 jours</v-chip>
        <v-chip size="small" variant="tonal" @click="periode(89)">90 jours</v-chip>
      </div>
      <div class="au-filtres">
        <v-text-field v-model="filtre.du" type="date" label="Du" variant="outlined" density="compact" hide-details />
        <v-text-field v-model="filtre.au" type="date" label="Au" variant="outlined" density="compact" hide-details />
        <v-select v-model="filtre.utilisateur" :items="utilisateurs" label="Utilisateur" clearable variant="outlined" density="compact" hide-details />
        <v-select v-model="filtre.module" :items="modules" label="Module" clearable variant="outlined" density="compact" hide-details />
        <v-select v-model="filtre.operation" :items="operations" label="Opération" clearable variant="outlined" density="compact" hide-details />
        <v-select v-model="filtre.resultat" :items="[{ title: 'Réussies', value: 'OK' }, { title: 'Échecs / refus', value: 'ECHEC' }]" label="Résultat" clearable
          variant="outlined" density="compact" hide-details />
        <v-select v-model="filtre.terminal" :items="terminaux" label="Terminal" clearable variant="outlined" density="compact" hide-details />
        <v-select v-model="filtre.systeme" :items="systemes" label="Système" clearable variant="outlined" density="compact" hide-details />
        <v-text-field v-model="filtre.q" label="Recherche (chemin, IP, n°…)" prepend-inner-icon="mdi-magnify" clearable variant="outlined" density="compact" hide-details
          @keyup.enter="charger()" />
      </div>
      <div class="au-actions">
        <v-btn color="primary" variant="flat" size="small" prepend-icon="mdi-filter-outline" :loading="chargement" @click="charger()">Appliquer</v-btn>
        <v-btn variant="text" size="small" @click="reinitialiser">Réinitialiser</v-btn>
      </div>
    </v-card>

    <!-- Synthèse -->
    <template v-if="synthese">
      <div class="au-cartes">
        <v-card class="classroom-card au-carte"><span class="au-carte__val">{{ synthese.total }}</span><span class="au-carte__lib">actions enregistrées</span></v-card>
        <v-card class="classroom-card au-carte"><span class="au-carte__val">{{ synthese.utilisateursActifs }}</span><span class="au-carte__lib">utilisateurs actifs</span></v-card>
        <v-card class="classroom-card au-carte" :class="{ 'au-carte--alerte': synthese.echecs }"><span class="au-carte__val">{{ synthese.echecs }}</span><span class="au-carte__lib">échecs / refus</span></v-card>
        <v-card class="classroom-card au-carte" :class="{ 'au-carte--alerte': synthese.connexionsRefusees }"><span class="au-carte__val">{{ synthese.connexionsRefusees }}</span><span class="au-carte__lib">connexions refusées</span></v-card>
      </div>
      <v-alert v-if="synthese.tronque" type="warning" variant="tonal" density="compact" class="mb-4">
        La période contient beaucoup d'actions : seules les plus récentes sont analysées. Réduisez la période pour tout voir.
      </v-alert>

      <div class="au-graphes">
        <v-card class="classroom-card pa-4">
          <h2 class="au-titre">Par utilisateur</h2>
          <div v-for="u in synthese.parUtilisateur.slice(0, 8)" :key="u.cle" class="au-barre" @click="filtre.utilisateur = u.cle; charger()">
            <span class="au-barre__lib">{{ u.cle }}</span>
            <span class="au-barre__fond"><span class="au-barre__val" :style="{ width: pct(u.total, max(synthese.parUtilisateur)) }" /></span>
            <span class="au-barre__n">{{ u.total }}<small v-if="u.echecs" class="au-echec"> · {{ u.echecs }} ✗</small></span>
          </div>
        </v-card>
        <v-card class="classroom-card pa-4">
          <h2 class="au-titre">Par module</h2>
          <div v-for="u in synthese.parModule.slice(0, 8)" :key="u.cle" class="au-barre" @click="filtre.module = u.cle; charger()">
            <span class="au-barre__lib">{{ u.cle }}</span>
            <span class="au-barre__fond"><span class="au-barre__val au-barre__val--b" :style="{ width: pct(u.total, max(synthese.parModule)) }" /></span>
            <span class="au-barre__n">{{ u.total }}<small v-if="u.echecs" class="au-echec"> · {{ u.echecs }} ✗</small></span>
          </div>
        </v-card>
        <v-card class="classroom-card pa-4">
          <h2 class="au-titre">Par terminal</h2>
          <div v-for="u in synthese.parTerminal" :key="u.cle" class="au-barre" @click="filtre.terminal = u.cle; charger()">
            <span class="au-barre__lib"><v-icon :icon="iconeTerminal(u.cle)" size="14" class="mr-1" />{{ u.cle }}</span>
            <span class="au-barre__fond"><span class="au-barre__val au-barre__val--d" :style="{ width: pct(u.total, max(synthese.parTerminal)) }" /></span>
            <span class="au-barre__n">{{ u.total }}<small v-if="u.echecs" class="au-echec"> · {{ u.echecs }} ✗</small></span>
          </div>
        </v-card>
        <v-card class="classroom-card pa-4">
          <h2 class="au-titre">Par système</h2>
          <div v-for="u in synthese.parSysteme.slice(0, 8)" :key="u.cle" class="au-barre" @click="filtre.systeme = u.cle; charger()">
            <span class="au-barre__lib">{{ u.cle }}</span>
            <span class="au-barre__fond"><span class="au-barre__val au-barre__val--e" :style="{ width: pct(u.total, max(synthese.parSysteme)) }" /></span>
            <span class="au-barre__n">{{ u.total }}<small v-if="u.echecs" class="au-echec"> · {{ u.echecs }} ✗</small></span>
          </div>
        </v-card>
        <v-card class="classroom-card pa-4">
          <h2 class="au-titre">Par jour</h2>
          <div v-for="j in synthese.parJour.slice(-10)" :key="j.jour" class="au-barre">
            <span class="au-barre__lib">{{ new Date(j.jour).toLocaleDateString('fr-FR') }}</span>
            <span class="au-barre__fond"><span class="au-barre__val au-barre__val--c" :style="{ width: pct(j.total, max(synthese.parJour)) }" /></span>
            <span class="au-barre__n">{{ j.total }}<small v-if="j.echecs" class="au-echec"> · {{ j.echecs }} ✗</small></span>
          </div>
        </v-card>
      </div>
    </template>

    <!-- Événements -->
    <v-card class="classroom-card pa-0 mt-4">
      <v-skeleton-loader v-if="chargement && !resultat" type="table" />
      <div v-else-if="resultat" class="au-table-wrap">
        <v-table density="compact" hover>
          <thead>
            <tr><th>Date</th><th>Utilisateur</th><th>Module</th><th>Opération</th><th>Ressource</th><th>Résultat</th><th>Terminal</th><th>IP</th></tr>
          </thead>
          <tbody>
            <tr v-for="(e, i) in resultat.evenements" :key="i" class="au-ligne" @click="detail = e">
              <td class="text-no-wrap">{{ fmtDate(e.horodatage) }}</td>
              <td>{{ e.email || '—' }}<small v-if="e.roles?.length" class="au-roles"> {{ e.roles.join(', ') }}</small></td>
              <td>{{ e.module || 'authentification' }}</td>
              <td><v-chip size="x-small" variant="tonal" :color="couleurOperation(e.operation)">{{ e.operation }}</v-chip></td>
              <td>{{ e.ressourceId ? '#' + e.ressourceId : '' }}</td>
              <td><v-chip size="x-small" :color="e.reussi ? 'success' : 'error'" variant="tonal">{{ e.reussi ? 'OK' : 'Échec' }}{{ e.statut ? ' ' + e.statut : '' }}</v-chip></td>
              <td class="text-no-wrap" :class="{ 'text-medium-emphasis': e.terminal === 'Inconnu' }" :title="e.agent || 'En-tête du navigateur non enregistré'">
                <v-icon :icon="iconeTerminal(e.terminal)" size="16" class="mr-1" />{{ e.terminal }}<small v-if="systemeConnu(e)" class="au-roles"> {{ e.systeme }}</small>
              </td>
              <td>{{ e.ip }}</td>
            </tr>
            <tr v-if="!resultat.evenements.length"><td colspan="8" class="text-center text-medium-emphasis py-6">Aucune action sur cette période avec ces filtres.</td></tr>
          </tbody>
        </v-table>
        <div class="au-pagination">
          <span>{{ resultat.total }} action(s)</span>
          <v-spacer />
          <v-btn icon="mdi-chevron-left" size="small" variant="text" :disabled="page === 0" @click="aller(-1)" />
          <span>Page {{ page + 1 }} / {{ nbPages }}</span>
          <v-btn icon="mdi-chevron-right" size="small" variant="text" :disabled="page + 1 >= nbPages" @click="aller(1)" />
        </div>
      </div>
    </v-card>

    <!-- Détail d'un événement -->
    <v-dialog :model-value="!!detail" max-width="560" @update:model-value="v => { if (!v) detail = null }">
      <v-card v-if="detail" class="pa-5">
        <h2 class="au-titre">{{ detail.module || 'authentification' }} · {{ detail.operation }}</h2>
        <dl class="au-detail">
          <div><dt>Date</dt><dd>{{ fmtDate(detail.horodatage) }}</dd></div>
          <div><dt>Utilisateur</dt><dd>{{ detail.email || '—' }} {{ detail.roles?.join(', ') }}</dd></div>
          <div><dt>Adresse IP</dt><dd>{{ detail.ip }}</dd></div>
          <div>
            <dt>Terminal</dt>
            <dd><v-icon :icon="iconeTerminal(detail.terminal)" size="16" class="mr-1" />{{ detail.terminal }}<template v-if="systemeConnu(detail)"> · {{ detail.systeme }}</template></dd>
          </div>
          <div v-if="detail.agent"><dt>Client</dt><dd>{{ detail.agent }}</dd></div>
          <div><dt>Requête</dt><dd>{{ detail.methode }} {{ detail.chemin }}<template v-if="detail.requete">?{{ detail.requete }}</template></dd></div>
          <div><dt>Ressource</dt><dd>{{ detail.ressourceId ? '#' + detail.ressourceId : '—' }}</dd></div>
          <div><dt>Résultat</dt><dd>{{ detail.reussi ? 'Réussi' : 'Échec' }} {{ detail.statut ?? '' }} <template v-if="detail.dureeMs != null">({{ detail.dureeMs }} ms)</template></dd></div>
          <div v-if="detail.detail"><dt>Détail</dt><dd>{{ detail.detail }}</dd></div>
        </dl>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="detail = null">Fermer</v-btn></v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Agent IA -->
    <v-dialog v-model="iaOuvert" max-width="760" scrollable>
      <v-card>
        <v-card-title class="d-flex align-center ga-2"><v-icon icon="mdi-robot-outline" /> Agent IA du journal d'audit</v-card-title>
        <v-card-text>
          <p class="au-aide">
            Posez une question en français ; l'agent répond à partir des actions de la période
            <strong>{{ new Date(filtre.du).toLocaleDateString('fr-FR') }} → {{ new Date(filtre.au).toLocaleDateString('fr-FR') }}</strong>
            (modifiez-la dans les filtres). Citez un utilisateur ou un module pour cibler la réponse.
            Seules des données du journal (e-mails, opérations, adresses IP, type de terminal et système) sont transmises au fournisseur d'IA, jamais le contenu des saisies.
          </p>
          <div class="au-suggestions">
            <v-chip v-for="s in SUGGESTIONS" :key="s" size="small" variant="outlined" @click="demander(s)">{{ s }}</v-chip>
          </div>
          <v-textarea v-model="question" label="Votre question" rows="2" auto-grow variant="outlined" density="comfortable" hide-details counter="600"
            placeholder="Ex. Qu'a fait caissier@… hier ? Y a-t-il eu des suppressions dans les ventes ?" @keydown.ctrl.enter="demander()" />
          <div class="mt-3"><v-btn color="primary" prepend-icon="mdi-send" :loading="iaChargement" :disabled="!question.trim()" @click="demander()">Demander</v-btn></div>

          <v-alert v-if="iaErreur" type="error" variant="tonal" class="mt-4">{{ iaErreur }}</v-alert>
          <div v-if="iaReponse" class="au-reponse mt-4">
            <p class="au-reponse__texte">{{ iaReponse.reponse }}</p>
            <template v-if="iaReponse.pointsCles.length">
              <h3 class="au-titre mt-3">Points clés</h3>
              <ul><li v-for="(p, i) in iaReponse.pointsCles" :key="i">{{ p }}</li></ul>
            </template>
            <v-alert v-if="iaReponse.alertes.length" type="warning" variant="tonal" density="compact" class="mt-3" title="À vérifier">
              <ul class="au-liste"><li v-for="(a, i) in iaReponse.alertes" :key="i">{{ a }}</li></ul>
            </v-alert>
            <p v-if="iaReponse.limites" class="au-aide mt-3"><v-icon icon="mdi-information-outline" size="14" /> {{ iaReponse.limites }}</p>
            <p class="au-aide">
              Réponse fondée sur {{ iaReponse.evenementsTransmis }} action(s)<template v-if="iaReponse.echantillon"> (échantillon pertinent)</template>
              sur {{ iaReponse.evenementsPeriode }} de la période. L'IA peut se tromper : vérifiez dans le tableau avant toute décision.
            </p>
          </div>
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="iaOuvert = false">Fermer</v-btn></v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.au-page { max-width: 1200px; }
.au-head-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.au-periodes { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.au-filtres { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 10px; }
.au-actions { display: flex; gap: 8px; margin-top: 12px; }
.au-cartes { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 12px; margin-bottom: 12px; }
.au-carte { padding: 14px 16px; display: flex; flex-direction: column; }
.au-carte__val { font-size: 1.7rem; font-weight: 800; line-height: 1.1; }
.au-carte__lib { font-size: 0.78rem; color: #4b5563; }
.au-carte--alerte .au-carte__val { color: #b91c1c; }
.au-graphes { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 12px; }
.au-titre { font-size: 0.95rem; font-weight: 700; margin: 0 0 8px; }
.au-barre { display: grid; grid-template-columns: minmax(90px, 1.2fr) 2fr auto; gap: 8px; align-items: center; font-size: 0.78rem; margin-bottom: 5px; cursor: pointer; }
.au-barre__lib { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.au-barre__fond { background: #eef2f1; border-radius: 4px; height: 10px; display: block; }
.au-barre__val { display: block; height: 10px; border-radius: 4px; background: var(--color-primary, #15803d); }
.au-barre__val--b { background: #2563eb; }
.au-barre__val--c { background: #7c3aed; }
.au-barre__val--d { background: #0891b2; }
.au-barre__val--e { background: #d97706; }
.au-echec { color: #b91c1c; }
.au-table-wrap { overflow-x: auto; }
.au-ligne { cursor: pointer; }
.au-roles { color: #6b7280; margin-left: 4px; }
.au-pagination { display: flex; align-items: center; gap: 8px; padding: 8px 16px; font-size: 0.82rem; }
.au-detail { margin: 0; display: grid; gap: 8px; font-size: 0.86rem; }
.au-detail div { display: flex; gap: 10px; }
.au-detail dt { font-weight: 700; min-width: 90px; }
.au-detail dd { margin: 0; word-break: break-all; }
.au-aide { font-size: 0.78rem; color: #4b5563; margin: 0 0 10px; }
.au-suggestions { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 12px; }
.au-reponse { background: #f6f8f7; border-radius: 10px; padding: 14px 16px; }
.au-reponse__texte { white-space: pre-wrap; margin: 0; line-height: 1.55; }
.au-liste { margin: 0; padding-left: 18px; }
</style>
