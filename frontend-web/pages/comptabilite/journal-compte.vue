<script setup lang="ts">
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] })

interface Ligne {
  date: string
  note: string | null
  objet: string | null
  beneficiaire: string | null
  comptes: string | null
  piece: string | null
  annulee: boolean
  entree: number | null
  sortie: number | null
  solde: number
}

interface JournalCompte {
  compteNumero: string
  compteLibelle: string
  du: string
  au: string
  devise: DeviseCaisse
  taux?: number | null
  soldeOuverture: number
  totalEntrees: number
  totalSorties: number
  soldeCloture: number
  lignes: Ligne[]
}

const api = useApi()
const route = useRoute()
const parametresStore = useParametresStore()
const loading = ref(false)
const erreur = ref('')
const journal = ref<JournalCompte | null>(null)
const dateImpression = ref('')

// Mois en cours par défaut ; compte de l'adresse (?compte=571) ou la caisse.
const dateLocale = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const maintenant = new Date()
const filtres = reactive({
  compte: (typeof route.query.compte === 'string' ? route.query.compte : '571') as string | null,
  du: dateLocale(new Date(maintenant.getFullYear(), maintenant.getMonth(), 1)),
  au: dateLocale(maintenant),
  devise: 'USD' as DeviseCaisse,
})

// Filtres d'affichage : ils masquent des lignes sans toucher au solde, calculé par le serveur sur toute la période.
const recherche = ref('')
const sens = ref<'TOUS' | 'ENTREES' | 'SORTIES'>('TOUS')
const masquerAnnulees = ref(false)

async function charger() {
  if (!filtres.compte) {
    erreur.value = 'Choisissez un compte.'
    return
  }
  loading.value = true
  erreur.value = ''
  try {
    const params = new URLSearchParams({ compte: filtres.compte, du: filtres.du, au: filtres.au, devise: filtres.devise })
    journal.value = await api<JournalCompte>(`/comptabilite/journal-compte?${params}`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le journal du compte.')
    journal.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  parametresStore.charger()
  charger()
})
watch(() => [filtres.compte, filtres.du, filtres.au, filtres.devise], charger)

function moisCourant(decalage: number) {
  const base = new Date(filtres.du + 'T00:00:00')
  const debut = new Date(base.getFullYear(), base.getMonth() + decalage, 1)
  const fin = new Date(debut.getFullYear(), debut.getMonth() + 1, 0)
  filtres.du = dateLocale(debut)
  filtres.au = dateLocale(fin > maintenant ? maintenant : fin)
}

const normaliser = (t: string | null | undefined) =>
  (t || '').normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase()

const lignesAffichees = computed(() => {
  const q = normaliser(recherche.value.trim())
  return (journal.value?.lignes || []).filter((l) => {
    if (sens.value === 'ENTREES' && !l.entree) return false
    if (sens.value === 'SORTIES' && !l.sortie) return false
    if (masquerAnnulees.value && l.annulee) return false
    if (!q) return true
    return [l.note, l.objet, l.beneficiaire, l.comptes, l.piece].some(v => normaliser(v).includes(q))
  })
})
const filtreActif = computed(() => !!recherche.value.trim() || sens.value !== 'TOUS' || masquerAnnulees.value)
const entreesAffichees = computed(() => lignesAffichees.value.reduce((s, l) => s + (l.entree || 0), 0))
const sortiesAffichees = computed(() => lignesAffichees.value.reduce((s, l) => s + (l.sortie || 0), 0))

// Trésorerie (classe 5) : entrées et sorties d'argent ; autre compte : débit et crédit.
const tresorerie = computed(() => (journal.value?.compteNumero || filtres.compte || '').startsWith('5'))
const libEntree = computed(() => (tresorerie.value ? 'Entrée' : 'Débit'))
const libSortie = computed(() => (tresorerie.value ? 'Sortie' : 'Crédit'))

const deviseAffichee = computed<DeviseCaisse>(() => journal.value?.devise ?? filtres.devise)
const fmtNombre = (v: number | null | undefined) => {
  if (v === null || v === undefined) return ''
  const decimales = deviseAffichee.value === 'USD' ? 2 : 0
  return new Intl.NumberFormat('fr-FR', { minimumFractionDigits: decimales, maximumFractionDigits: decimales }).format(v)
}
const fmtDate = (d: string) => d.slice(8, 10) + '/' + d.slice(5, 7)
const fmtDateLongue = (d: string) => new Date(d + 'T00:00:00').toLocaleDateString('fr-FR')
const fmtTaux = (t: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 4 }).format(t)

// « Journal de caisse d'octobre 2026 » pour un mois entier, sinon « du … au … ».
const titreDocument = computed(() => {
  const j = journal.value
  if (!j) return 'Journal du compte'
  const nom = j.compteNumero.startsWith('57') ? 'Journal de caisse' : `Journal du compte ${j.compteNumero} — ${j.compteLibelle}`
  const du = new Date(j.du + 'T00:00:00')
  const au = new Date(j.au + 'T00:00:00')
  const finDuMois = new Date(du.getFullYear(), du.getMonth() + 1, 0)
  if (du.getDate() === 1 && au.getTime() === finDuMois.getTime()) {
    const mois = du.toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' })
    return `${nom} ${/^[aeiouyh]/i.test(mois) ? "d'" : 'de '}${mois}`
  }
  return `${nom} du ${fmtDateLongue(j.du)} au ${fmtDateLongue(j.au)}`
})

function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  nextTick(() => window.print())
}
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Journal des comptes</h1>
        <p class="page-subtitle">Chaque mouvement d'un compte au choix, avec sa note, son bénéficiaire et le solde cumulé</p>
      </div>
      <v-btn v-if="journal" color="primary" variant="flat" rounded="lg" prepend-icon="mdi-printer" @click="imprimer">
        Imprimer
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-card class="classroom-card pa-4 mb-4 no-print">
      <div class="jc-filtres">
        <div class="jc-filtres__compte">
          <ComptabiliteSelecteurCompte v-model="filtres.compte" label="Compte" :seulement-actifs="false" />
        </div>
        <v-text-field v-model="filtres.du" type="date" label="Du" density="comfortable" variant="outlined" hide-details />
        <v-text-field v-model="filtres.au" type="date" label="Au" density="comfortable" variant="outlined" hide-details />
        <v-btn-toggle v-model="filtres.devise" mandatory density="comfortable" variant="outlined" color="primary" divided>
          <v-btn value="USD">USD</v-btn>
          <v-btn value="CDF">FC</v-btn>
        </v-btn-toggle>
      </div>
      <div class="jc-filtres mt-3">
        <v-btn variant="text" size="small" prepend-icon="mdi-chevron-left" @click="moisCourant(-1)">Mois précédent</v-btn>
        <v-btn variant="text" size="small" @click="moisCourant(0)">Mois en cours</v-btn>
        <v-btn variant="text" size="small" append-icon="mdi-chevron-right" @click="moisCourant(1)">Mois suivant</v-btn>
      </div>
      <div class="jc-filtres mt-3">
        <v-text-field
          v-model="recherche" class="jc-filtres__recherche" density="comfortable" variant="outlined" hide-details clearable
          prepend-inner-icon="mdi-magnify" label="Rechercher (note, objet, bénéficiaire, compte, pièce)"
        />
        <v-btn-toggle v-model="sens" mandatory density="comfortable" variant="outlined" color="primary" divided>
          <v-btn value="TOUS">Tous</v-btn>
          <v-btn value="ENTREES">{{ libEntree }}s</v-btn>
          <v-btn value="SORTIES">{{ libSortie }}s</v-btn>
        </v-btn-toggle>
        <v-checkbox v-model="masquerAnnulees" label="Masquer les pièces annulées" density="compact" hide-details />
      </div>
    </v-card>

    <v-progress-linear v-if="loading" indeterminate color="primary" class="mb-4 no-print" />

    <template v-if="journal">
      <!-- En-tête d'impression : papier à en-tête de l'entreprise (visible seulement sur le document imprimé) -->
      <div class="etat-print-header print-entete">
        <div class="etat-print-header__brand print-entete__marque">
          <div class="etat-print-header__logo print-entete__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
            <v-icon v-else icon="mdi-finance" size="16" color="white" />
          </div>
          <div>
            <span class="etat-print-header__company print-entete__nom">{{ parametresStore.parametres.nom }}</span>
            <span class="etat-print-header__doc">Journal du compte {{ journal.compteNumero }}</span>
          </div>
        </div>
        <div class="etat-print-header__meta print-entete__meta">
          <span>Période : <strong>{{ fmtDateLongue(journal.du) }} au {{ fmtDateLongue(journal.au) }}</strong></span>
          <span>Devise : <strong>{{ uniteCaisse(deviseAffichee) }}</strong><template v-if="journal.taux"> (1 USD = {{ fmtTaux(journal.taux) }} FC)</template></span>
          <span>Imprimé le : {{ dateImpression }}</span>
        </div>
      </div>

      <h2 class="jc-titre">{{ titreDocument }}</h2>
      <p v-if="filtreActif" class="jc-note">
        Lignes filtrées : {{ lignesAffichees.length }} sur {{ journal.lignes.length }}. Le solde reste celui du compte, toutes lignes comprises.
      </p>

      <div class="jc-table-wrap">
        <table class="jc-table">
          <thead>
            <tr>
              <th>Date</th>
              <th>Note</th>
              <th>Objet</th>
              <th>Bénéficiaire</th>
              <th>Compte</th>
              <th class="num">{{ libEntree }}</th>
              <th class="num">{{ libSortie }}</th>
              <th class="num">Solde</th>
            </tr>
          </thead>
          <tbody>
            <tr class="jc-ouverture">
              <td>{{ fmtDate(journal.du) }}</td>
              <td colspan="6">Solde d'ouverture</td>
              <td class="num">{{ fmtNombre(journal.soldeOuverture) }}</td>
            </tr>
            <tr v-for="(l, i) in lignesAffichees" :key="i" :class="{ 'jc-annulee': l.annulee }">
              <td>{{ fmtDate(l.date) }}</td>
              <td class="jc-nowrap">{{ l.note ? l.note.replace(/^NF-\d{4}-0*/, 'NF-') : '' }}</td>
              <td>
                {{ l.objet }}
                <span v-if="l.annulee" class="jc-badge">annulée</span>
                <span v-if="l.piece" class="jc-piece no-print">{{ l.piece }}</span>
              </td>
              <td>{{ l.beneficiaire }}</td>
              <td>{{ l.comptes }}</td>
              <td class="num">{{ fmtNombre(l.entree) }}</td>
              <td class="num">{{ fmtNombre(l.sortie) }}</td>
              <td class="num">{{ fmtNombre(l.solde) }}</td>
            </tr>
            <tr v-if="!lignesAffichees.length">
              <td colspan="8" class="jc-vide">Aucun mouvement sur cette période{{ filtreActif ? ' avec ces filtres' : '' }}.</td>
            </tr>
          </tbody>
          <tfoot>
            <tr>
              <td colspan="5">Totaux{{ filtreActif ? ' des lignes affichées' : ' de la période' }}</td>
              <td class="num">{{ fmtNombre(filtreActif ? entreesAffichees : journal.totalEntrees) }}</td>
              <td class="num">{{ fmtNombre(filtreActif ? sortiesAffichees : journal.totalSorties) }}</td>
              <td class="num">{{ fmtNombre(journal.soldeCloture) }}</td>
            </tr>
          </tfoot>
        </table>
      </div>
      <p class="jc-note">
        Solde de clôture au {{ fmtDateLongue(journal.au) }} : <strong>{{ fmtMontantCaisse(journal.soldeCloture, deviseAffichee) }}</strong>.
        Un compte inclut ses sous-comptes ; une pièce annulée reste au journal, son extourne la solde (SYSCOHADA).
      </p>
    </template>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; flex-wrap: wrap; }
.jc-filtres { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.jc-filtres > .v-text-field { flex: 0 1 170px; }
.jc-filtres__compte { flex: 1 1 280px; min-width: 0; }
.jc-filtres .jc-filtres__recherche { flex: 1 1 320px; }
.jc-titre { font-size: 1.35rem; font-weight: 800; color: #1f2d4d; margin: 4px 0 8px; padding-bottom: 6px; border-bottom: 2px solid #1f3864; }
.jc-note { font-size: 0.78rem; color: #6b7280; margin: 6px 0 10px; }
.jc-table-wrap { overflow-x: auto; }
.jc-table { width: 100%; border-collapse: collapse; font-size: 0.82rem; background: #fff; }
.jc-table th { background: #1f3864; color: #fff; text-align: left; font-weight: 700; padding: 6px 8px; white-space: nowrap; }
.jc-table td { padding: 5px 8px; border-bottom: 1px solid #e5e7eb; vertical-align: top; color: #111827; }
.jc-table .num { text-align: right; white-space: nowrap; font-variant-numeric: tabular-nums; }
.jc-table tbody tr:nth-child(even) { background: #f7f8fb; }
.jc-nowrap { white-space: nowrap; }
.jc-ouverture td { font-style: italic; color: #374151; background: #eef1f7; }
.jc-table tfoot td { font-weight: 700; border-top: 2px solid #1f3864; background: #eef1f7; }
.jc-annulee td { color: #9ca3af; }
.jc-badge { display: inline-block; margin-left: 6px; padding: 0 6px; border-radius: 8px; font-size: 0.68rem; background: #fee2e2; color: #b91c1c; }
.jc-piece { display: block; font-size: 0.68rem; color: #9ca3af; }
.jc-vide { text-align: center; color: #6b7280; padding: 16px; }

@media print {
  .jc-titre { font-size: 1.1rem; }
  .jc-table { font-size: 0.7rem; }
  .jc-table th { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
  .jc-table tbody tr:nth-child(even), .jc-ouverture td, .jc-table tfoot td { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
  .jc-table thead { display: table-header-group; }
  .jc-table tr { break-inside: avoid; }
  .jc-table-wrap { overflow: visible; }
}
</style>
