<script setup lang="ts">
// Comptabilité analytique de gestion (CAGE) — SYSCOHADA révisé, classe 9,
// comptes 92 à 99. Le référentiel ne fixe que ces rubriques à deux chiffres :
// l'entité les subdivise librement (ex. 9411 Coût atelier A).
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] })

interface LigneAnalytique {
  id: number
  numero: string
  libelle: string
  niveau: number
  parentNumero: string | null
  rubrique: boolean
  manuel: boolean
  imputable: boolean
  actif: boolean
  soldeAnterieurDebit: number
  soldeAnterieurCredit: number
  mouvementDebit: number
  mouvementCredit: number
  soldeFinalDebiteur: number
  soldeFinalCrediteur: number
}

interface EtatAnalytique {
  du: string
  au: string
  lignes: LigneAnalytique[]
  totalMouvementDebit: number
  totalMouvementCredit: number
  totalSoldeDebiteur: number
  totalSoldeCrediteur: number
  equilibre: boolean
}

// Rubriques officielles du SYSCOHADA révisé (classe 9, comptes 92 à 99).
const RUBRIQUES: { numero: string; titre: string; role: string }[] = [
  { numero: '92', titre: 'Comptes réfléchis', role: 'Contrepartie des charges, produits et stocks de la comptabilité générale repris en analytique.' },
  { numero: '93', titre: 'Comptes de reclassements', role: 'Reclassement des charges et produits par destination (fonctions, sections, centres d\'analyse).' },
  { numero: '94', titre: 'Comptes de coûts', role: 'Coûts d\'achat, de production, de distribution et de revient.' },
  { numero: '95', titre: 'Comptes de stocks', role: 'Inventaire permanent des stocks valorisés aux coûts calculés.' },
  { numero: '96', titre: 'Écarts sur coûts préétablis', role: 'Écarts entre coûts réels et coûts standards ou budgétés.' },
  { numero: '97', titre: 'Différences de traitement comptable', role: 'Charges non incorporables, charges supplétives, différences d\'inventaire.' },
  { numero: '98', titre: 'Comptes de résultats', role: 'Résultats analytiques par produit, activité ou centre de profit.' },
  { numero: '99', titre: 'Liaisons internes', role: 'Liaisons entre établissements ou avec une comptabilité générale autonome.' },
]

const api = useApi()
const permissions = usePermissionsStore()
const peutModifier = computed(() => permissions.peutModifier('COMPTABILITE'))

const loading = ref(false)
const erreur = ref('')
const succes = ref('')
const etat = ref<EtatAnalytique | null>(null)
const afficherRubriques = ref(false)

const filtres = reactive({
  du: new Date().getFullYear() + '-01-01',
  au: new Date().toISOString().slice(0, 10),
})

onMounted(charger)

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const params = new URLSearchParams({ du: filtres.du, au: filtres.au })
    etat.value = await api<EtatAnalytique>(`/comptabilite/analytique?${params}`)
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger la comptabilité analytique.'
    etat.value = null
  } finally {
    loading.value = false
  }
}

// --- Création d'un sous-compte ------------------------------------------
const dialogue = ref(false)
const enregistrement = ref(false)
const erreurDialogue = ref('')
const form = reactive({ parentNumero: '', suffixe: '', libelle: '' })
const numeroFinal = computed(() => form.parentNumero + form.suffixe)
const formValide = computed(() => /^\d{1,6}$/.test(form.suffixe) && form.libelle.trim().length > 0)

function ouvrirCreation(parent: LigneAnalytique) {
  form.parentNumero = parent.numero
  form.suffixe = ''
  form.libelle = ''
  erreurDialogue.value = ''
  dialogue.value = true
}

async function creerSousCompte() {
  if (!formValide.value) return
  enregistrement.value = true
  erreurDialogue.value = ''
  try {
    await api('/comptabilite/analytique/comptes', {
      method: 'POST',
      body: { parentNumero: form.parentNumero, suffixe: form.suffixe, libelle: form.libelle.trim() },
    })
    succes.value = `Compte ${numeroFinal.value} — ${form.libelle.trim()} créé.`
    dialogue.value = false
    await charger()
  } catch (e: any) {
    erreurDialogue.value = e?.data?.message || 'Impossible de créer le sous-compte.'
  } finally {
    enregistrement.value = false
  }
}

function fmtUSD(v: number) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(v || 0)
}

function montant(v: number) {
  return v ? fmtUSD(v) : '—'
}

function fmtDate(d: string) {
  return new Date(d).toLocaleDateString('fr-FR')
}

const nbSousComptes = computed(() => etat.value?.lignes.filter((l) => !l.rubrique).length ?? 0)

const headers = [
  { title: 'Compte', key: 'numero', sortable: false },
  { title: 'Ant. débit', key: 'soldeAnterieurDebit', align: 'end' as const, sortable: false },
  { title: 'Ant. crédit', key: 'soldeAnterieurCredit', align: 'end' as const, sortable: false },
  { title: 'Mvt débit', key: 'mouvementDebit', align: 'end' as const, sortable: false },
  { title: 'Mvt crédit', key: 'mouvementCredit', align: 'end' as const, sortable: false },
  { title: 'Solde débiteur', key: 'soldeFinalDebiteur', align: 'end' as const, sortable: false },
  { title: 'Solde créditeur', key: 'soldeFinalCrediteur', align: 'end' as const, sortable: false },
  { title: '', key: 'actions', align: 'end' as const, sortable: false },
]
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Comptabilité analytique</h1>
        <p class="page-sub">Comptes 92 à 99 — comptabilité analytique de gestion, SYSCOHADA révisé (classe 9)</p>
      </div>
      <v-btn variant="tonal" rounded="lg" prepend-icon="mdi-information-outline" @click="afficherRubriques = !afficherRubriques">
        Rubriques SYSCOHADA
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" closable class="mb-4" @click:close="succes = ''">{{ succes }}</v-alert>

    <v-expand-transition>
      <v-card v-if="afficherRubriques" class="classroom-card pa-6 mb-4">
        <p class="text-body-2 mb-4">
          Le SYSCOHADA révisé laisse l'usage des comptes 92 à 99 à l'initiative de chaque entité, qui les
          subdivise selon sa structure, sa politique des coûts et son organisation. Les huit rubriques à deux chiffres :
        </p>
        <v-row dense>
          <v-col v-for="r in RUBRIQUES" :key="r.numero" cols="12" md="6">
            <div class="rubrique">
              <span class="rubrique__num">{{ r.numero }}</span>
              <div>
                <div class="rubrique__titre">{{ r.titre }}</div>
                <div class="rubrique__role">{{ r.role }}</div>
              </div>
            </div>
          </v-col>
        </v-row>
      </v-card>
    </v-expand-transition>

    <v-card class="classroom-card pa-6 mb-4">
      <div class="ana-filtres">
        <v-text-field v-model="filtres.du" label="Du" type="date" variant="outlined" density="comfortable" rounded="lg" hide-details />
        <v-text-field v-model="filtres.au" label="Au" type="date" variant="outlined" density="comfortable" rounded="lg" hide-details />
        <v-btn color="primary" rounded="lg" prepend-icon="mdi-refresh" class="ana-filtres__btn" :loading="loading" @click="charger">
          Actualiser
        </v-btn>
      </div>
    </v-card>

    <template v-if="etat">
      <v-row class="mb-4">
        <v-col cols="12" sm="6" md="3">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">Mouvements débit</div>
            <div class="kpi-value">{{ fmtUSD(etat.totalMouvementDebit) }}</div>
          </v-card>
        </v-col>
        <v-col cols="12" sm="6" md="3">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">Mouvements crédit</div>
            <div class="kpi-value">{{ fmtUSD(etat.totalMouvementCredit) }}</div>
          </v-card>
        </v-col>
        <v-col cols="12" sm="6" md="3">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">Équilibre</div>
            <div class="kpi-value">
              <v-chip :color="etat.equilibre ? 'success' : 'error'" size="small" variant="flat">
                {{ etat.equilibre ? 'Équilibrée' : 'Déséquilibrée' }}
              </v-chip>
            </div>
          </v-card>
        </v-col>
        <v-col cols="12" sm="6" md="3">
          <v-card class="classroom-card pa-4">
            <div class="kpi-label">Sous-comptes analytiques</div>
            <div class="kpi-value">{{ nbSousComptes }}</div>
            <div class="kpi-sub">{{ fmtDate(etat.du) }} → {{ fmtDate(etat.au) }}</div>
          </v-card>
        </v-col>
      </v-row>

      <v-card class="classroom-card">
        <v-data-table :headers="headers" :items="etat.lignes" :loading="loading" items-per-page="-1" hide-default-footer>
          <template #item.numero="{ item }">
            <div class="compte" :class="{ 'compte--rubrique': item.rubrique, 'compte--inactif': !item.actif }"
                 :style="{ paddingLeft: item.niveau * 20 + 'px' }">
              <span class="compte__num">{{ item.numero }}</span>
              <span>{{ item.libelle }}</span>
              <v-chip v-if="item.manuel" size="x-small" variant="tonal" color="primary" class="ml-2">entité</v-chip>
              <v-chip v-if="!item.actif" size="x-small" variant="tonal" class="ml-2">inactif</v-chip>
            </div>
          </template>
          <template #item.soldeAnterieurDebit="{ item }">{{ montant(item.soldeAnterieurDebit) }}</template>
          <template #item.soldeAnterieurCredit="{ item }">{{ montant(item.soldeAnterieurCredit) }}</template>
          <template #item.mouvementDebit="{ item }">{{ montant(item.mouvementDebit) }}</template>
          <template #item.mouvementCredit="{ item }">{{ montant(item.mouvementCredit) }}</template>
          <template #item.soldeFinalDebiteur="{ item }">
            <span :class="{ 'font-weight-bold': item.rubrique }">{{ montant(item.soldeFinalDebiteur) }}</span>
          </template>
          <template #item.soldeFinalCrediteur="{ item }">
            <span :class="{ 'font-weight-bold': item.rubrique }">{{ montant(item.soldeFinalCrediteur) }}</span>
          </template>
          <template #item.actions="{ item }">
            <v-btn v-if="peutModifier && item.actif" icon="mdi-plus" size="small" variant="text"
                   :title="`Ajouter un sous-compte à ${item.numero}`" @click="ouvrirCreation(item)" />
          </template>
          <template #no-data>
            Aucun compte analytique : appliquez la migration V126 (comptes 92 à 99).
          </template>
        </v-data-table>
      </v-card>
    </template>

    <v-dialog v-model="dialogue" max-width="480">
      <v-card class="pa-2">
        <v-card-title>Nouveau sous-compte analytique</v-card-title>
        <v-card-text>
          <v-alert v-if="erreurDialogue" type="error" variant="tonal" density="compact" class="mb-4">{{ erreurDialogue }}</v-alert>
          <v-text-field :model-value="form.parentNumero" label="Compte parent" variant="outlined" density="comfortable" readonly class="mb-2" />
          <v-text-field v-model="form.suffixe" label="Suffixe (chiffres)" variant="outlined" density="comfortable"
                        inputmode="numeric" maxlength="6" :rules="[(v: string) => /^\d{1,6}$/.test(v) || '1 à 6 chiffres']"
                        :hint="form.suffixe ? `Numéro du compte : ${numeroFinal}` : 'Ex. 11 → ' + form.parentNumero + '11'" persistent-hint class="mb-2" />
          <v-text-field v-model="form.libelle" label="Libellé" variant="outlined" density="comfortable" maxlength="200"
                        placeholder="Ex. Coût atelier A" />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="dialogue = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :disabled="!formValide" :loading="enregistrement" @click="creerSousCompte">Créer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.kpi-label { font-size: 0.72rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.1rem; font-weight: 700; color: #111827; margin-top: 4px; }
.kpi-sub { font-size: 0.8rem; color: #6b7280; margin-top: 4px; }
.ana-filtres { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.ana-filtres :deep(.v-input) { flex: 1 1 200px; }
.ana-filtres__btn { height: 48px; flex: 0 0 auto; }
.compte { display: flex; align-items: center; gap: 8px; }
.compte__num { font-family: monospace; min-width: 56px; color: #374151; }
.compte--rubrique { font-weight: 700; }
.compte--inactif { opacity: 0.55; }
.rubrique { display: flex; gap: 12px; align-items: flex-start; }
.rubrique__num { font-weight: 700; font-family: monospace; color: rgb(var(--v-theme-primary)); min-width: 24px; }
.rubrique__titre { font-weight: 600; }
.rubrique__role { font-size: 0.85rem; color: #6b7280; }
@media (max-width: 600px) {
  .ana-filtres__btn { width: 100%; }
}
</style>
