<script setup lang="ts">
import { useExerciceStore } from '~/stores/exercice'
// Audit SYSCOHADA du 10/11/2026 (C-09, C-19, C-25) : grand livre général, centralisation des journaux, fichier des
// écritures pour l'administration fiscale et journal des événements comptables (chaîné, en ajout seul).
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] })

interface LigneGL { date: string, journal: string | null, piece: string | null, libelle: string, debit: number, credit: number, solde: number }
interface CompteGL { numero: string, libelle: string, reportDebit: number, reportCredit: number, lignes: LigneGL[], totalDebit: number, totalCredit: number, soldeFinal: number }
interface Centralisation { journal: string, annee: number, mois: number, pieces: number, debit: number, credit: number }
interface Evenement { id: number, horodatage: string, auteur: string, action: string, objetType: string, objetReference: string | null, avant: string | null, apres: string | null, motif: string | null, empreinte: string }
interface Evenements { chaineIntacte: boolean, premiereLigneAlteree: number | null, total: number, evenements: Evenement[] }

const api = useApi()
const onglet = ref('grand-livre')
const du = ref(useExerciceStore().debut)
const au = ref(useExerciceStore().fin)
const loading = ref(false)
const erreur = ref('')

const grandLivre = ref<CompteGL[]>([])
// Filtre du grand livre (15/11/2026) : un compte choisi parmi ceux mouvementés sur la période ; vide = tous.
const compteChoisi = ref<string | null>(null)
const comptesGL = computed(() => grandLivre.value.map(c => ({ value: c.numero, title: `${c.numero} — ${c.libelle}` })))
const grandLivreAffiche = computed(() =>
  compteChoisi.value ? grandLivre.value.filter(c => c.numero === compteChoisi.value) : grandLivre.value)
const centralisation = ref<Centralisation[]>([])
const evenements = ref<Evenements | null>(null)

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    if (onglet.value === 'grand-livre') {
      grandLivre.value = await api<CompteGL[]>('/comptabilite/grand-livre-general', { query: { du: du.value, au: au.value } })
    } else if (onglet.value === 'centralisation') {
      centralisation.value = await api<Centralisation[]>('/comptabilite/centralisation', { query: { du: du.value, au: au.value } })
    } else {
      evenements.value = await api<Evenements>('/comptabilite/evenements', { query: { limite: 300 } })
    }
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Chargement impossible.')
  } finally {
    loading.value = false
  }
}
watch(onglet, charger)

async function exporterEcritures() {
  erreur.value = ''
  try {
    await telechargerFichier(api, `/comptabilite/ecritures/export?du=${du.value}&au=${au.value}`,
      `Ecritures_${du.value}_${au.value}.csv`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible d'exporter les écritures.")
  }
}

const totauxCentralisation = computed(() => centralisation.value.reduce(
  (t, l) => ({ debit: t.debit + l.debit, credit: t.credit + l.credit }), { debit: 0, credit: 0 }))

function imprimer() {
  window.print()
}

onMounted(charger)

const fmt = (v: number) => new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(v || 0)
const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '')
const fmtMois = (a: number, m: number) => new Date(a, m - 1, 1).toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' })
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Éditions comptables</h1>
        <p class="page-sub">Livres obligatoires et piste d'audit</p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-4 mb-4 no-print">
      <v-tabs v-model="onglet" color="primary" class="mb-3 ed-onglets" show-arrows>
        <v-tab value="grand-livre">Grand livre général</v-tab>
        <v-tab value="centralisation">Centralisation des journaux</v-tab>
        <v-tab value="evenements">Journal des événements</v-tab>
      </v-tabs>
      <div v-if="onglet !== 'evenements'" class="d-flex flex-wrap ga-3 align-center ed-filtres">
        <v-text-field v-model="du" type="date" label="Du" variant="outlined" density="compact" hide-details style="max-width: 180px" />
        <v-text-field v-model="au" type="date" label="Au" variant="outlined" density="compact" hide-details style="max-width: 180px" />
        <v-btn color="primary" :loading="loading" class="ed-afficher" @click="charger">Afficher</v-btn>
        <v-autocomplete v-if="onglet === 'grand-livre'" v-model="compteChoisi" :items="comptesGL"
          label="Compte" placeholder="Tous les comptes" prepend-inner-icon="mdi-magnify"
          variant="outlined" density="compact" hide-details clearable class="ed-compte" style="min-width: 280px; max-width: 420px"
          no-data-text="Aucun compte mouvementé sur la période" />
        <v-btn variant="outlined" prepend-icon="mdi-printer" class="ed-action" @click="imprimer">Imprimer</v-btn>
        <v-btn variant="outlined" prepend-icon="mdi-file-delimited-outline" class="ed-action" @click="exporterEcritures">
          Fichier des écritures (CSV)
        </v-btn>
      </div>
    </v-card>

    <!-- ── Grand livre général ───────────────────────────────────────── -->
    <div v-if="onglet === 'grand-livre'">
      <h2 class="print-only">
        Grand livre {{ compteChoisi ? `du compte ${compteChoisi}` : 'général' }} du {{ fmtDate(du) }} au {{ fmtDate(au) }}
      </h2>
      <v-progress-linear v-if="loading" indeterminate class="mb-2" />
      <div v-if="!loading && !grandLivre.length" class="text-medium-emphasis">Aucune écriture sur la période.</div>
      <v-card v-for="c in grandLivreAffiche" :key="c.numero" class="classroom-card pa-4 mb-3 compte">
        <div class="font-weight-bold mb-2">{{ c.numero }} — {{ c.libelle }}</div>
        <div class="tableau">
          <table>
            <thead>
              <tr><th>Date</th><th>Journal</th><th>Pièce</th><th>Libellé</th><th class="nb">Débit</th><th class="nb">Crédit</th><th class="nb">Solde</th></tr>
            </thead>
            <tbody>
              <tr class="report">
                <td colspan="4" class="ed-titre-ligne">Report au {{ fmtDate(du) }}</td>
                <td class="nb ed-m" data-label="Débit">{{ fmt(c.reportDebit) }}</td><td class="nb ed-m" data-label="Crédit">{{ fmt(c.reportCredit) }}</td>
                <td class="nb ed-s" data-label="Solde">{{ fmt(c.reportDebit - c.reportCredit) }}</td>
              </tr>
              <tr v-for="(l, i) in c.lignes" :key="i" class="ed-ligne">
                <td class="ed-date">{{ fmtDate(l.date) }}</td><td class="ed-journal">{{ l.journal }}</td>
                <td class="ed-piece">{{ l.piece }}</td><td class="ed-libelle">{{ l.libelle }}</td>
                <td class="nb ed-m" data-label="Débit">{{ l.debit ? fmt(l.debit) : '—' }}</td>
                <td class="nb ed-m" data-label="Crédit">{{ l.credit ? fmt(l.credit) : '—' }}</td>
                <td class="nb ed-s" data-label="Solde">{{ fmt(l.solde) }}</td>
              </tr>
              <tr class="total">
                <td colspan="4" class="ed-titre-ligne">Total de la période</td>
                <td class="nb ed-m" data-label="Débit">{{ fmt(c.totalDebit) }}</td><td class="nb ed-m" data-label="Crédit">{{ fmt(c.totalCredit) }}</td>
                <td class="nb ed-s" data-label="Solde final">{{ fmt(c.soldeFinal) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </v-card>
    </div>

    <!-- ── Centralisation ────────────────────────────────────────────── -->
    <v-card v-else-if="onglet === 'centralisation'" class="classroom-card pa-4">
      <h2 class="print-only">Centralisation des journaux du {{ fmtDate(du) }} au {{ fmtDate(au) }}</h2>
      <div class="tableau">
        <table>
          <thead><tr><th>Mois</th><th>Journal</th><th class="nb">Pièces</th><th class="nb">Débit</th><th class="nb">Crédit</th></tr></thead>
          <tbody>
            <tr v-for="(l, i) in centralisation" :key="i" class="ed-ligne">
              <td class="ed-date">{{ fmtMois(l.annee, l.mois) }}</td><td class="ed-journal">{{ l.journal }}</td>
              <td class="nb ed-s" data-label="Pièces">{{ l.pieces }}</td>
              <td class="nb ed-m" data-label="Débit">{{ fmt(l.debit) }}</td><td class="nb ed-m" data-label="Crédit">{{ fmt(l.credit) }}</td>
            </tr>
            <tr class="total">
              <td colspan="3" class="ed-titre-ligne">Total</td>
              <td class="nb ed-m" data-label="Débit">{{ fmt(totauxCentralisation.debit) }}</td><td class="nb ed-m" data-label="Crédit">{{ fmt(totauxCentralisation.credit) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </v-card>

    <!-- ── Journal des événements ────────────────────────────────────── -->
    <v-card v-else class="classroom-card pa-4">
      <v-alert v-if="evenements" :type="evenements.chaineIntacte ? 'success' : 'error'" variant="tonal" class="mb-3">
        {{ evenements.chaineIntacte
          ? `Chaîne d'empreintes intacte : ${evenements.total} événement(s), aucune ligne modifiée ni supprimée.`
          : `Chaîne rompue à l'événement n° ${evenements.premiereLigneAlteree} : une ligne a été modifiée ou supprimée.` }}
      </v-alert>
      <div class="tableau">
        <table>
          <thead><tr><th>N°</th><th>Date</th><th>Auteur</th><th>Action</th><th>Objet</th><th>Motif</th><th>Détail</th></tr></thead>
          <tbody>
            <tr v-for="e in evenements?.evenements ?? []" :key="e.id" class="ed-ligne">
              <td class="ed-date">N° {{ e.id }}</td>
              <td class="ed-journal">{{ new Date(e.horodatage).toLocaleString('fr-FR') }}</td>
              <td data-label="Auteur">{{ e.auteur }}</td>
              <td data-label="Action">{{ e.action }}</td>
              <td class="ed-libelle" data-label="Objet">{{ e.objetType }} {{ e.objetReference }}</td>
              <td class="ed-libelle" data-label="Motif">{{ e.motif }}</td>
              <td class="ed-libelle">
                <details>
                  <summary>avant / après</summary>
                  <pre class="json">{{ e.avant }}</pre>
                  <pre class="json">{{ e.apres }}</pre>
                  <div class="text-caption empreinte">{{ e.empreinte }}</div>
                </details>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </v-card>
  </div>
</template>

<style scoped>
.tableau {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.85rem;
}
th, td {
  padding: 4px 8px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.08);
  text-align: left;
  vertical-align: top;
}
.nb {
  text-align: right;
  white-space: nowrap;
}
.report td, .total td {
  font-weight: 600;
  background: rgba(0, 0, 0, 0.03);
}
.json {
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 0.75rem;
  max-width: 480px;
}
.empreinte {
  font-family: monospace;
  word-break: break-all;
}
.print-only {
  display: none;
}
@media print {
  .no-print {
    display: none !important;
  }
  .print-only {
    display: block;
    font-size: 1.1rem;
    margin-bottom: 8px;
  }
  .compte {
    break-inside: avoid;
  }
}

/* ── Téléphone (21/11/2026) : onglets en pilules, actions pleine largeur, chaque ligne en carte ───────────────────── */
@media screen and (max-width: 699px) {
  .ed-onglets { border-radius: 14px; background: color-mix(in srgb, var(--color-primary) 7%, transparent); padding: 4px; height: auto !important; }
  .ed-onglets :deep(.v-tab) { border-radius: 10px; min-width: 0; padding-inline: 12px; font-size: 0.82rem; text-transform: none; letter-spacing: 0; }
  .ed-onglets :deep(.v-tab--selected) { background: var(--verre-fort); box-shadow: var(--verre-ombre); }
  .ed-onglets :deep(.v-tab__slider) { display: none; }

  .ed-filtres { display: grid !important; grid-template-columns: 1fr 1fr; gap: 10px !important; }
  .ed-filtres > * { max-width: none !important; min-width: 0 !important; width: 100%; }
  .ed-filtres .ed-afficher, .ed-filtres .ed-compte { grid-column: 1 / -1; }
  .ed-filtres .v-btn { height: 46px !important; }
  .ed-filtres .ed-action { font-size: 0.78rem; padding-inline: 8px; }

  .compte { padding: 14px !important; }
  .tableau { overflow: visible; }
  .tableau table, .tableau tbody { display: block; }
  .tableau thead { display: none; }
  .tableau tr {
    display: grid; grid-template-columns: 1fr 1fr; gap: 6px 10px; margin-bottom: 8px; padding: 12px 14px;
    border-radius: 16px; border: 1px solid var(--verre-bord);
    background: color-mix(in srgb, var(--verre-fort) 70%, transparent);
  }
  .tableau td { display: block; padding: 0; border: 0; text-align: left; background: transparent !important; }
  .tableau td[data-label]::before {
    content: attr(data-label); display: block; font-size: 0.6rem; font-weight: 700; letter-spacing: 0.6px;
    text-transform: uppercase; color: var(--ink-faint); margin-bottom: 1px;
  }
  .ed-date { font-weight: 800; }
  .ed-journal { text-align: right !important; color: var(--ink-soft); font-size: 0.78rem; }
  .ed-piece { grid-column: 1 / -1; font-size: 0.78rem; color: var(--ink-soft); }
  .ed-libelle { grid-column: 1 / -1; font-weight: 600; }
  .ed-titre-ligne { grid-column: 1 / -1; font-size: 0.7rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.6px; color: var(--ink-soft); }
  .tableau td.ed-m, .tableau td.ed-s { padding: 7px 10px !important; border-radius: 10px; font-weight: 800; text-align: left !important;
    background: color-mix(in srgb, var(--color-primary) 7%, transparent) !important; }
  .tableau td.ed-s { grid-column: 1 / -1; background: color-mix(in srgb, var(--color-primary) 12%, transparent) !important; }
  .report, .total { background: color-mix(in srgb, var(--color-primary) 9%, var(--verre)) !important; }
  .json { max-width: 100%; }
}
</style>
