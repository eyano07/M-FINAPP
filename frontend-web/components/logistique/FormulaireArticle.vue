<script setup lang="ts">
// Boîte de saisie d'un article du catalogue, partagée par trois pages qui ne mélangent plus les natures (16/11/2026) :
//   - Logistique › Articles      : MARCHANDISE (biens stockés destinés à la vente, minerais compris) ;
//   - Logistique › Services      : SERVICE (vendable, ni stock, ni entrepôt, ni coût des ventes) ;
//   - Patrimoine › Consommables  : CONSOMMABLE (stocké, consommé en interne, jamais vendu).
// La nature est fixée par la page : plus de sélecteur de type dans la boîte. Les biens durables de l'entreprise
// (immobilier, matériel roulant…) ne sont pas des articles : ils vivent dans Patrimoine › Immobilisations.
// Usage : <LogistiqueFormulaireArticle ref="formulaire" type="SERVICE" :taux-change="taux" @enregistre="charger" />
// puis formulaire.value.ouvrir() (création) ou formulaire.value.ouvrir(article) (modification).

export type TypeArticleCatalogue = 'MARCHANDISE' | 'SERVICE' | 'CONSOMMABLE'

export interface ArticleCatalogue {
  id: number
  code: string
  libelle: string
  uniteMesure?: string
  type: string
  compteStockNumero?: string
  compteChargeNumero?: string
  compteProduitNumero?: string
  compteAchatNumero?: string
  minerais?: boolean
  prixVente?: number
  prixAchat?: number
  soumisTva: boolean
  stockMin: number
  actif: boolean
}

const props = defineProps<{ type: TypeArticleCatalogue; tauxChange: number }>()
const emit = defineEmits<{ enregistre: [] }>()

const api = useApi()
const dialog = ref(false)
const saving = ref(false)
const erreur = ref('')
const editId = ref<number | null>(null)

const NOMS: Record<TypeArticleCatalogue, { nouveau: string; modifier: string; icone: string }> = {
  MARCHANDISE: { nouveau: 'Nouvel article à vendre', modifier: "Modifier l'article", icone: 'mdi-package-variant-closed' },
  SERVICE: { nouveau: 'Nouveau service', modifier: 'Modifier le service', icone: 'mdi-hand-extended-outline' },
  CONSOMMABLE: { nouveau: 'Nouveau consommable', modifier: 'Modifier le consommable', icone: 'mdi-package-variant' },
}

const estService = computed(() => props.type === 'SERVICE')
const estConsommable = computed(() => props.type === 'CONSOMMABLE')
const estMarchandise = computed(() => props.type === 'MARCHANDISE')

const form = reactive({
  code: '',
  libelle: '',
  uniteMesure: '',
  compteStockNumero: '' as string | null,
  compteChargeNumero: '' as string | null,
  compteProduitNumero: '' as string | null,
  compteAchatNumero: '' as string | null,
  minerais: false,
  prixVenteUSD: null as number | null,
  prixAchatUSD: null as number | null,
  soumisTva: true,
  stockMin: 0,
  actif: true,
})

function ouvrir(a?: ArticleCatalogue) {
  erreur.value = ''
  editId.value = a?.id ?? null
  const taux = props.tauxChange
  Object.assign(form, {
    code: a?.code ?? '',
    libelle: a?.libelle ?? '',
    uniteMesure: a?.uniteMesure ?? '',
    compteStockNumero: a?.compteStockNumero ?? '',
    compteChargeNumero: a?.compteChargeNumero ?? '',
    compteProduitNumero: a?.compteProduitNumero ?? '',
    compteAchatNumero: a?.compteAchatNumero ?? '',
    minerais: !!a?.minerais,
    prixVenteUSD: a?.prixVente != null && taux > 0 ? a.prixVente / taux : null,
    prixAchatUSD: a?.prixAchat != null && taux > 0 ? a.prixAchat / taux : null,
    soumisTva: a?.soumisTva ?? true,
    stockMin: a?.stockMin ?? 0,
    actif: a?.actif ?? true,
  })
  dialog.value = true
}
defineExpose({ ouvrir })

async function enregistrer() {
  if (!form.code.trim() || !form.libelle.trim()) {
    erreur.value = 'Code et libellé sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    const taux = props.tauxChange
    // Les prix sont saisis en USD et stockés en FC — un choix indépendant de la devise de base du grand livre
    // (article.prixVente n'est jamais touché par ConversionDeviseService), géré côté client de bout en bout.
    const body = {
      code: form.code,
      libelle: form.libelle,
      uniteMesure: form.uniteMesure,
      type: props.type,
      compteStockNumero: estService.value ? null : form.compteStockNumero,
      compteChargeNumero: estService.value ? null : form.compteChargeNumero,
      compteAchatNumero: estService.value ? null : form.compteAchatNumero,
      minerais: estMarchandise.value ? form.minerais : false,
      // Un consommable ne se vend pas : ni prix de vente, ni compte de produit.
      compteProduitNumero: estConsommable.value ? null : form.compteProduitNumero,
      prixVente: estConsommable.value || form.prixVenteUSD == null ? null : form.prixVenteUSD * taux,
      // Un minerais n'a pas de prix d'achat au niveau de l'article : il se saisit chargement par chargement à la
      // réception du camion. Un service n'en a pas non plus.
      prixAchat: estService.value || form.minerais || form.prixAchatUSD == null ? null : form.prixAchatUSD * taux,
      soumisTva: form.soumisTva,
      stockMin: estService.value ? 0 : form.stockMin,
      actif: form.actif,
    }
    if (editId.value) {
      await api(`/logistique/articles/${editId.value}`, { method: 'PUT', body })
    } else {
      await api('/logistique/articles', { method: 'POST', body })
    }
    dialog.value = false
    emit('enregistre')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Enregistrement impossible.')
  } finally {
    saving.value = false
  }
}

const fc = (usd: number | null) => new Intl.NumberFormat('fr-FR').format(Math.round((usd || 0) * props.tauxChange))
</script>

<template>
  <v-dialog v-model="dialog" max-width="560" scrollable>
    <v-card class="dialog-card" rounded="xl">
      <div class="dialog-header">
        <v-icon :icon="NOMS[type].icone" size="28" color="white" class="mb-2" />
        <div class="text-h6 font-weight-bold text-white">
          {{ editId ? NOMS[type].modifier : NOMS[type].nouveau }}
        </div>
      </div>

      <v-card-text class="pa-6 pt-5">
        <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" density="compact">{{ erreur }}</v-alert>

        <!-- Minerais : suivi camion par camion, chaque chargement ayant son propre prix de vente
             (voir Logistique > Camions de minerais). -->
        <div v-if="estMarchandise" class="minerai-toggle mb-4">
          <div class="minerai-toggle__texte">
            <span class="minerai-toggle__titre">
              <v-icon icon="mdi-dump-truck" size="18" class="mr-1" />Minerais
            </span>
            <span class="minerai-toggle__desc">
              Suivi camion par camion : chaque chargement (plaque + date) se revend à son propre prix.
            </span>
          </div>
          <v-switch v-model="form.minerais" color="primary" density="compact" hide-details inset />
        </div>

        <v-text-field v-model="form.code" label="Code *" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.libelle" label="Libellé *" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.uniteMesure" :label="estService ? 'Unité (heure, forfait, jour…)' : 'Unité de mesure'"
          variant="outlined" density="comfortable" class="mb-3" />

        <!-- Achat (facultatif) : masqué pour un minerais (prix saisi au chargement) et pour un service. -->
        <template v-if="!estService && !form.minerais">
          <p class="art-section">Achat</p>
          <v-text-field
            v-model.number="form.prixAchatUSD"
            label="Prix d'achat HT (USD)"
            type="number" min="0" step="0.01"
            prepend-inner-icon="mdi-cart-outline"
            variant="outlined" density="comfortable"
            :hint="form.prixAchatUSD ? `≈ ${fc(form.prixAchatUSD)} FC` : 'Facultatif : prérempli à l\'achat en caisse, corrigeable au prix réellement payé'"
            persistent-hint
            class="mb-3"
          />
        </template>

        <!-- Vente : pas pour un consommable, qui ne se vend jamais. -->
        <template v-if="!estConsommable">
          <p class="art-section">Vente</p>
          <v-text-field
            v-model.number="form.prixVenteUSD"
            label="Prix de vente HT (USD)"
            type="number" min="0" step="0.01"
            prepend-inner-icon="mdi-currency-usd"
            variant="outlined" density="comfortable"
            :hint="form.prixVenteUSD ? `≈ ${fc(form.prixVenteUSD)} FC` : (estService ? 'Sans prix, le service ne peut pas être vendu' : 'Sans prix, l\'article ne peut pas être vendu')"
            persistent-hint
            class="mb-3"
          />
          <ComptabiliteSelecteurCompte
            v-model="form.compteProduitNumero"
            :label="estService ? 'Compte de produit (ex. 7061)' : 'Compte de produit (ex. 7011)'"
            class="mb-1"
          />
          <v-switch v-model="form.soumisTva" label="Soumis à la TVA" color="primary" density="compact" class="mb-2" />
        </template>

        <!-- Stock : marchandise et consommable. -->
        <template v-if="!estService">
          <p class="art-section">Stock</p>
          <v-alert type="info" variant="tonal" density="compact" class="mb-3">
            La quantité en stock s'ajoute depuis
            <NuxtLink to="/logistique/stock" class="text-primary">État du stock</NuxtLink>,
            une fois {{ estConsommable ? 'le consommable créé' : "l'article créé" }}.
          </v-alert>
          <ComptabiliteSelecteurCompte v-model="form.compteStockNumero"
            :label="estConsommable ? 'Compte de stock (ex. 331)' : 'Compte de stock (ex. 3111)'" class="mb-3" />
          <!-- 6031 et non 6012 : V23 a réaffecté 6012 en « Achats de marchandises hors Région ». -->
          <ComptabiliteSelecteurCompte v-model="form.compteChargeNumero"
            :label="estConsommable ? 'Compte de charge (ex. 6033)' : 'Compte de charge (ex. 6031)'" class="mb-3" />
          <ComptabiliteSelecteurCompte v-model="form.compteAchatNumero"
            :label="estConsommable ? 'Compte d\'achat (ex. 6041)' : 'Compte d\'achat (ex. 6011)'" class="mb-1" />
          <p class="text-caption text-medium-emphasis mb-3">
            Débité au règlement d'une note de frais d'achat de {{ estConsommable ? 'ce consommable' : 'cette marchandise' }}
            (voir « Note de frais »).
          </p>
          <v-text-field v-model.number="form.stockMin" label="Stock minimum" type="number" variant="outlined" density="comfortable" class="mb-3" />
        </template>
        <v-alert v-else type="info" variant="tonal" density="compact" class="mb-3">
          Un service n'est pas stocké : ni entrepôt, ni coût des ventes.
        </v-alert>

        <v-switch v-model="form.actif" label="Actif" color="primary" hide-details />
      </v-card-text>

      <v-card-actions class="px-6 pb-5 pt-0 gap-2">
        <v-btn variant="tonal" rounded="lg" :disabled="saving" @click="dialog = false">Annuler</v-btn>
        <v-spacer />
        <v-btn color="primary" variant="flat" rounded="lg" :loading="saving" prepend-icon="mdi-check" @click="enregistrer">
          Enregistrer
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.dialog-card { overflow: hidden; }
.dialog-header {
  padding: 26px 26px 20px;
  text-align: center;
  background: linear-gradient(140deg, #34d399 0%, var(--color-primary) 50%, var(--color-primary-darker) 100%);
}
.art-section {
  font-size: 0.72rem;
  font-weight: 700;
  color: var(--ink-faint);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 4px 0 10px;
}
.minerai-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 14px;
  border: 1px solid var(--hairline);
  border-radius: 12px;
  background: #fffbeb;
}
.minerai-toggle__texte { display: flex; flex-direction: column; gap: 2px; }
.minerai-toggle__titre { font-size: 0.9rem; font-weight: 700; color: #92400e; display: flex; align-items: center; }
.minerai-toggle__desc { font-size: 0.75rem; color: #a16207; line-height: 1.35; }
</style>

<style>
/* Thème nuit (généré le 17/11) : fonds pastel fixes remplacés par un fond sombre teinté. */
html[data-theme='dark'] .minerai-toggle { background: color-mix(in srgb, #f2c66d 11%, var(--surface)) !important; }
</style>
