<script setup lang="ts">
// Creation d'une piece : reservee au DFIN cote serveur.
// ADMIN ne modifie que les pages dediees a l'administration : la creation
// reste reservee au DFIN.
definePageMeta({ module: 'COMPTABILITE', niveau: 'ECRITURE', roles: ['DFIN'] })

/**
 * Saisie des a-nouveaux en UNE pièce équilibrée.
 *
 * Reprendre des soldes ligne à ligne depuis un journal produit autant de
 * pièces d'une seule ligne, donc déséquilibrées. Ici toutes les reprises
 * forment une seule pièce, marquée « solde d'ouverture » : la balance les
 * porte alors en colonnes d'ouverture et non en mouvements de la période —
 * sans quoi une reprise de stock ou de charge deviendrait un flux de
 * l'exercice et fausserait le compte de résultat.
 */
interface Ligne { compteNumero: string | null; debit: number | null; credit: number | null }

const api = useApi()
const router = useRouter()

const exercice = new Date().getFullYear()
const form = reactive({
  datePiece: `${exercice}-01-01`,
  libelle: `Reprise des à-nouveaux — exercice ${exercice}`,
})
const lignes = ref<Ligne[]>([
  { compteNumero: null, debit: null, credit: null },
  { compteNumero: null, debit: null, credit: null },
])

const saving = ref(false)
const erreur = ref('')
const succes = ref('')

function ajouterLigne() {
  lignes.value.push({ compteNumero: null, debit: null, credit: null })
}
function retirerLigne(i: number) {
  if (lignes.value.length > 2) lignes.value.splice(i, 1)
}

const totalDebit = computed(() => lignes.value.reduce((s, l) => s + (Number(l.debit) || 0), 0))
const totalCredit = computed(() => lignes.value.reduce((s, l) => s + (Number(l.credit) || 0), 0))
const ecart = computed(() => totalDebit.value - totalCredit.value)
const equilibre = computed(() => Math.abs(ecart.value) < 0.01 && totalDebit.value > 0)

const lignesValides = computed(() =>
  lignes.value.filter(l => l.compteNumero && ((Number(l.debit) || 0) > 0 || (Number(l.credit) || 0) > 0)))

const pretAEnregistrer = computed(() => equilibre.value && lignesValides.value.length >= 2)

async function enregistrer() {
  if (!pretAEnregistrer.value) return
  // Une ligne portant à la fois un débit et un crédit est ambiguë : le serveur
  // la refuserait, autant le dire ici avec la ligne en cause.
  const ambigue = lignesValides.value.findIndex(
    l => (Number(l.debit) || 0) > 0 && (Number(l.credit) || 0) > 0)
  if (ambigue >= 0) {
    erreur.value = `Ligne ${ambigue + 1} : une écriture ne peut pas porter à la fois un débit et un crédit.`
    return
  }

  saving.value = true
  erreur.value = ''
  try {
    const piece = await api<{ id: number; reference: string }>('/comptabilite/pieces', {
      method: 'POST',
      body: {
        datePiece: form.datePiece,
        journal: 'OPERATIONS_DIVERSES',
        libelle: form.libelle,
        soldeOuverture: true,
        lignes: lignesValides.value.map(l => ({
          compteNumero: l.compteNumero,
          debit: Number(l.debit) || 0,
          credit: Number(l.credit) || 0,
          libelle: form.libelle,
        })),
      },
    })
    // La reprise n'a d'effet sur les états qu'une fois comptabilisée : on
    // enchaîne, sinon elle resterait en brouillon sans alimenter la balance.
    await api(`/comptabilite/pieces/${piece.id}/comptabiliser`, { method: 'POST' })
    succes.value = `Solde d'ouverture enregistré et comptabilisé (${piece.reference}).`
    lignes.value = [
      { compteNumero: null, debit: null, credit: null },
      { compteNumero: null, debit: null, credit: null },
    ]
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement du solde d'ouverture.")
  } finally {
    saving.value = false
  }
}

const fmt = (v: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(v || 0)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Solde d'ouverture</h1>
        <p class="page-sub">Reprise des à-nouveaux en début d'exercice, en une seule pièce équilibrée</p>
      </div>
      <v-btn variant="tonal" rounded="lg" prepend-icon="mdi-file-document-multiple-outline"
        to="/comptabilite/pieces">
        Voir les pièces
      </v-btn>
    </div>

    <v-alert type="info" variant="tonal" class="mb-4">
      Cette pièce est marquée <strong>« solde d'ouverture »</strong> : la balance la porte en colonnes
      d'ouverture et non en mouvements de la période. Sans ce marqueur, une reprise de stock ou de
      charge serait comptée comme un flux de l'exercice et fausserait le compte de résultat.
    </v-alert>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-6 mb-4">
      <v-row>
        <v-col cols="12" md="4">
          <v-text-field v-model="form.datePiece" type="date" label="Date d'ouverture"
            variant="outlined" density="comfortable" hide-details />
        </v-col>
        <v-col cols="12" md="8">
          <v-text-field v-model="form.libelle" label="Libellé" variant="outlined"
            density="comfortable" hide-details />
        </v-col>
      </v-row>
    </v-card>

    <v-card class="classroom-card pa-5 mb-4">
      <div class="d-flex align-center justify-space-between mb-3">
        <span class="text-subtitle-2">Soldes à reprendre</span>
        <v-btn size="small" variant="tonal" color="primary" prepend-icon="mdi-plus" @click="ajouterLigne">
          Ajouter une ligne
        </v-btn>
      </div>

      <div v-for="(l, i) in lignes" :key="i" class="so-ligne">
        <div class="so-compte">
          <ComptabiliteSelecteurCompte v-model="l.compteNumero" label="Compte" />
        </div>
        <v-text-field v-model.number="l.debit" type="number" label="Débit" variant="outlined"
          density="compact" hide-details class="so-montant" />
        <v-text-field v-model.number="l.credit" type="number" label="Crédit" variant="outlined"
          density="compact" hide-details class="so-montant" />
        <v-btn icon="mdi-close" size="small" variant="text" :disabled="lignes.length <= 2"
          @click="retirerLigne(i)" />
      </div>

      <div class="so-totaux">
        <span>Totaux</span>
        <span class="so-total">{{ fmt(totalDebit) }}</span>
        <span class="so-total">{{ fmt(totalCredit) }}</span>
        <span style="width:40px" />
      </div>
    </v-card>

    <v-alert :type="equilibre ? 'success' : 'warning'" variant="tonal" class="mb-4">
      {{ equilibre
        ? 'Pièce équilibrée : total débit = total crédit.'
        : (totalDebit === 0 && totalCredit === 0
            ? 'Saisissez les soldes à reprendre : au moins deux lignes, débit et crédit égaux.'
            : `Écart de ${fmt(Math.abs(ecart))} — ${ecart > 0 ? 'il manque du crédit' : 'il manque du débit'}.`) }}
    </v-alert>

    <v-btn color="success" variant="flat" size="large" rounded="lg" prepend-icon="mdi-content-save"
      :loading="saving" :disabled="!pretAEnregistrer" @click="enregistrer">
      Enregistrer et comptabiliser
    </v-btn>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }

.so-ligne { display: flex; align-items: flex-start; gap: 10px; padding: 6px 0; }
.so-compte { flex: 1; min-width: 0; }
.so-montant { max-width: 160px; }
.so-totaux {
  display: flex; align-items: center; gap: 10px;
  margin-top: 10px; padding-top: 12px; border-top: 2px solid #e5e7eb;
  font-weight: 700; color: #111827;
}
.so-totaux > :first-child { flex: 1; }
.so-total { max-width: 160px; width: 160px; text-align: right; font-variant-numeric: tabular-nums; }

@media (max-width: 700px) {
  .so-ligne { flex-wrap: wrap; }
  .so-montant { max-width: 45%; }
  .so-totaux { display: none; }
}
</style>
