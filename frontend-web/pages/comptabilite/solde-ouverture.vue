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

const messageEquilibre = computed(() => {
  if (equilibre.value) return 'Pièce équilibrée : total débit = total crédit.'
  if (totalDebit.value === 0 && totalCredit.value === 0) return 'Saisissez les soldes à reprendre : au moins deux lignes, débit et crédit égaux.'
  return `Écart de ${fmt(Math.abs(ecart.value))} — ${ecart.value > 0 ? 'il manque du crédit' : 'il manque du débit'}.`
})
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
      <br>
      Les comptes de trésorerie (classe 5 : caisse, banques, mobile money) n'y figurent pas : leur solde ne
      se saisit pas ici, pas plus que tout autre mouvement de trésorerie.
    </v-alert>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-4 pa-sm-6 mb-4">
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

    <v-card class="classroom-card pa-4 pa-sm-5 mb-4">
      <div class="d-flex align-center justify-space-between mb-3">
        <span class="text-subtitle-2">Soldes à reprendre</span>
        <v-btn size="small" variant="tonal" color="primary" prepend-icon="mdi-plus" @click="ajouterLigne">
          Ajouter une ligne
        </v-btn>
      </div>

      <CommunLigneSaisie v-for="(l, i) in lignes" :key="i" :numero="i + 1" :retirable="lignes.length > 2"
        @retirer="retirerLigne(i)">
        <div class="ls-large">
          <ComptabiliteSelecteurCompte v-model="l.compteNumero" label="Compte" exclure-prefixe="5" />
        </div>
        <v-text-field v-model.number="l.debit" type="number" label="Débit" variant="outlined"
          density="compact" hide-details class="ls-montant" />
        <v-text-field v-model.number="l.credit" type="number" label="Crédit" variant="outlined"
          density="compact" hide-details class="ls-montant" />
      </CommunLigneSaisie>

      <div class="so-totaux so-totaux--pc">
        <span>Totaux</span>
        <span class="so-total">{{ fmt(totalDebit) }}</span>
        <span class="so-total">{{ fmt(totalCredit) }}</span>
        <span style="width:40px" />
      </div>
    </v-card>

    <div class="so-pied">
      <!-- Téléphone et tablette : totaux et équilibre toujours à l'écran, avec le bouton d'enregistrement. -->
      <div class="so-recap">
        <div class="so-recap__case">
          <span class="so-recap__etiquette">Total débit</span>
          <span class="so-recap__valeur">{{ fmt(totalDebit) }}</span>
        </div>
        <div class="so-recap__case">
          <span class="so-recap__etiquette">Total crédit</span>
          <span class="so-recap__valeur">{{ fmt(totalCredit) }}</span>
        </div>
        <div class="so-recap__case" :class="equilibre ? 'so-recap__case--ok' : 'so-recap__case--ecart'">
          <span class="so-recap__etiquette">Écart</span>
          <span class="so-recap__valeur">{{ fmt(Math.abs(ecart)) }}</span>
        </div>
      </div>

      <p class="so-recap__etat" :class="equilibre ? 'so-recap__etat--ok' : 'so-recap__etat--ecart'">{{ messageEquilibre }}</p>
      <v-alert :type="equilibre ? 'success' : 'warning'" variant="tonal" class="mb-4 so-pied__alerte">{{ messageEquilibre }}</v-alert>

      <v-btn color="success" variant="flat" size="large" rounded="lg" prepend-icon="mdi-content-save"
        class="so-pied__bouton" :loading="saving" :disabled="!pretAEnregistrer" @click="enregistrer">
        Enregistrer et comptabiliser
      </v-btn>
    </div>
  </div>
</template>

<style scoped>
.so-totaux {
  display: flex; align-items: center; gap: 10px;
  margin-top: 10px; padding-top: 12px; border-top: 2px solid #e5e7eb;
  font-weight: 700; color: #111827;
}
.so-totaux > :first-child { flex: 1; }
.so-total { max-width: 160px; width: 160px; text-align: right; font-variant-numeric: tabular-nums; }

/* Récapitulatif collant : seulement sous 960 px (au-dessus, les totaux sont sous les colonnes et le bouton suit). */
.so-recap, .so-recap__etat { display: none; }
@media screen and (max-width: 959px) {
  .so-totaux--pc { display: none; }
  .so-pied {
    position: sticky;
    bottom: 0;
    z-index: 4;
    margin: 0 -12px -12px;
    padding: 10px 12px 12px;
    background: #fff;
    border-top: 1px solid #e8ecef;
    box-shadow: 0 -6px 16px rgba(15, 23, 42, 0.06);
  }
  .so-recap { display: flex; gap: 8px; margin-bottom: 8px; }
  .so-recap__case { flex: 1 1 0; min-width: min-content; padding: 6px 10px 7px; background: #f6f8fa; border-radius: 10px; }
  .so-recap__case--ok { background: color-mix(in srgb, #16a34a 12%, white); }
  .so-recap__case--ecart { background: color-mix(in srgb, #f59e0b 16%, white); }
  .so-recap__etiquette { display: block; font-size: 0.64rem; font-weight: 700; letter-spacing: 0.5px; text-transform: uppercase; white-space: nowrap; color: #94a3b8; }
  .so-recap__valeur { display: block; margin-top: 1px; font-size: 0.95rem; font-weight: 800; font-variant-numeric: tabular-nums; white-space: nowrap; color: #0f172a; }
  /* Le message d'équilibre tient en une ligne compacte (pas d'alerte) ; le bouton prend toute la largeur. */
  .so-pied__alerte { display: none; }
  .so-recap__etat { display: block; margin: 0 0 8px; font-size: 0.78rem; line-height: 1.3; }
  .so-recap__etat--ok { color: #15803d; }
  .so-recap__etat--ecart { color: #b45309; }
  .so-pied__bouton { width: 100%; }
}
@media screen and (min-width: 600px) and (max-width: 959px) {
  .so-pied { margin: 0 -16px -16px; padding-left: 16px; padding-right: 16px; }
}
</style>
