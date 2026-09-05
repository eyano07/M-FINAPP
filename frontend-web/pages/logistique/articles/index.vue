<script setup lang="ts">
// Le caissier a LOGISTIQUE en LECTURE pour charger le catalogue depuis
// « Nouvelle vente » (V29), pas pour consulter les ecrans du domaine : la
// garde par roles l'exclut sans retirer la permission de module.
definePageMeta({ module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'GEST_PATRIMOINE', 'ADMIN'] })

interface Article {
  id: number
  code: string
  libelle: string
  uniteMesure?: string
  type: 'MARCHANDISE' | 'SERVICE' | 'CONSOMMABLE' | 'PLAT' | 'BOISSON' | 'PROVISION'
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
  vendable: boolean
}

const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const articles = ref<Article[]>([])
const tauxChange = ref(1)
const dialog = ref(false)
const editId = ref<number | null>(null)

const canWrite = computed(() => auth.hasAnyRole(['LOGISTIQUE', 'DFIN']))

// Le catalogue est partagé : il contient aussi les consommables du patrimoine
// et la carte du restaurant. Sans ce dictionnaire, tout ce qui n'était pas un
// service s'affichait « Marchandise », y compris un plat.
const META_TYPE: Record<string, { label: string; couleur: string }> = {
  MARCHANDISE: { label: 'Marchandise', couleur: 'teal' },
  SERVICE: { label: 'Service', couleur: 'purple' },
  CONSOMMABLE: { label: 'Consommable', couleur: 'blue-grey' },
  PLAT: { label: 'Plat', couleur: 'deep-orange' },
  BOISSON: { label: 'Boisson', couleur: 'indigo' },
  PROVISION: { label: 'Provision', couleur: 'brown' },
}
// Types créés depuis un autre module (Patrimoine, Restaurant) : on les affiche
// mais on ne laisse pas cet écran les rebasculer silencieusement en
// marchandise, ce qui leur ferait perdre leurs comptes d'imputation.
const TYPES_AUTRES_MODULES = ['PLAT', 'BOISSON', 'PROVISION']
const typeExterne = computed(() => TYPES_AUTRES_MODULES.includes(form.type))

const form = reactive({
  code: '',
  libelle: '',
  uniteMesure: '',
  type: 'MARCHANDISE' as 'MARCHANDISE' | 'SERVICE' | 'CONSOMMABLE' | 'PLAT' | 'BOISSON' | 'PROVISION',
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

const estService = computed(() => form.type === 'SERVICE')

// ── Filtres (écran + impression, qui n'imprime que le résultat filtré) ────
const filtreRecherche = ref('')
const filtreType = ref('TOUS')
const filtreStatut = ref<'TOUS' | 'ACTIFS' | 'INACTIFS'>('TOUS')

const typesPresents = computed(() => {
  const presents = [...new Set(articles.value.map(a => a.type))]
  return presents.map(t => ({ title: META_TYPE[t]?.label ?? t, value: t }))
})

const articlesFiltres = computed(() => {
  const q = filtreRecherche.value.trim().toLowerCase()
  return articles.value.filter((a) => {
    if (q && !a.code.toLowerCase().includes(q) && !a.libelle.toLowerCase().includes(q)) return false
    if (filtreType.value !== 'TOUS' && a.type !== filtreType.value) return false
    if (filtreStatut.value === 'ACTIFS' && !a.actif) return false
    if (filtreStatut.value === 'INACTIFS' && a.actif) return false
    return true
  })
})

const resumeFiltres = computed(() => {
  const parts: string[] = []
  if (filtreType.value !== 'TOUS') parts.push(META_TYPE[filtreType.value]?.label ?? filtreType.value)
  if (filtreStatut.value !== 'TOUS') parts.push(filtreStatut.value === 'ACTIFS' ? 'Actifs' : 'Inactifs')
  if (filtreRecherche.value.trim()) parts.push(`« ${filtreRecherche.value.trim()} »`)
  return parts.length ? parts.join(' · ') : 'Tous les articles'
})

// Le tableau pagine ne rend que la page courante dans le DOM : sans bascule
// vers "toutes les lignes" au moment d'imprimer, seule la 1re page sortirait
// sur le papier — meme mecanique que balance-verification/index.vue.
const lignesParPage = ref(15)
const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  window.print()
}

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [arts, tauxData] = await Promise.all([
      api<Article[]>('/logistique/articles'),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })) /* 0 et non 1 : un repli a 1 affichait les
        montants FC tels quels comme des USD (surevaluation d'un facteur
        egal au taux, ~2800x) sans que rien ne le signale. A 0, les
        convertisseurs (tous gardes par `taux > 0`) renvoient 0, valeur
        manifestement fausse plutot que plausible. */,
    ])
    articles.value = arts
    // 0 et non 1 : evite d'enregistrer un prix FC egal au prix USD.
    tauxChange.value = tauxData.taux || 0
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les articles.')
  } finally {
    loading.value = false
  }
}
onMounted(() => {
  charger()
  window.addEventListener('beforeprint', () => { lignesParPage.value = -1 })
  window.addEventListener('afterprint', () => { lignesParPage.value = 15 })
})

function ouvrirCreation() {
  editId.value = null
  Object.assign(form, {
    code: '', libelle: '', uniteMesure: '', type: 'MARCHANDISE',
    compteStockNumero: '', compteChargeNumero: '', compteProduitNumero: '', compteAchatNumero: '', minerais: false,
    prixVenteUSD: null, prixAchatUSD: null, soumisTva: true, stockMin: 0, actif: true,
  })
  dialog.value = true
}

function ouvrirEdition(a: Article) {
  editId.value = a.id
  Object.assign(form, {
    code: a.code,
    libelle: a.libelle,
    uniteMesure: a.uniteMesure || '',
    type: a.type || 'MARCHANDISE',
    compteStockNumero: a.compteStockNumero || '',
    compteChargeNumero: a.compteChargeNumero || '',
    compteProduitNumero: a.compteProduitNumero || '',
    compteAchatNumero: a.compteAchatNumero || '',
    minerais: !!a.minerais,
    prixVenteUSD: a.prixVente != null && tauxChange.value > 0 ? a.prixVente / tauxChange.value : null,
    prixAchatUSD: a.prixAchat != null && tauxChange.value > 0 ? a.prixAchat / tauxChange.value : null,
    soumisTva: a.soumisTva,
    stockMin: a.stockMin,
    actif: a.actif,
  })
  dialog.value = true
}

async function enregistrer() {
  if (!form.code.trim() || !form.libelle.trim()) {
    erreur.value = 'Code et libellé sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    // Les prix sont saisis en USD et stockés en FC — un choix independant
    // de la devise de base du grand livre (article.prixVente n'est jamais
    // touche par ConversionDeviseService), gere cote client de bout en bout.
    const body = {
      code: form.code,
      libelle: form.libelle,
      uniteMesure: form.uniteMesure,
      type: form.type,
      compteStockNumero: estService.value ? null : form.compteStockNumero,
      compteChargeNumero: estService.value ? null : form.compteChargeNumero,
      compteAchatNumero: estService.value ? null : form.compteAchatNumero,
      minerais: estService.value ? false : form.minerais,
      compteProduitNumero: form.compteProduitNumero,
      prixVente: form.prixVenteUSD != null ? form.prixVenteUSD * tauxChange.value : null,
      // Un minerais n'a pas de prix d'achat au niveau de l'article : il se
      // saisit chargement par chargement a la reception du camion.
      prixAchat: form.minerais || form.prixAchatUSD == null
        ? null
        : form.prixAchatUSD * tauxChange.value,
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
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Enregistrement impossible.')
  } finally {
    saving.value = false
  }
}

const fmtUSD = (fc?: number) =>
  fc == null ? '—'
    : new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 })
        .format(tauxChange.value > 0 ? fc / tauxChange.value : 0)
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Articles</h1>
        <p class="page-sub">Catalogue : marchandises stockées et services vendables</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn v-if="articlesFiltres.length" color="error" variant="tonal" rounded="lg"
          prepend-icon="mdi-printer-outline" @click="imprimer">
          Imprimer
        </v-btn>
        <v-btn v-if="canWrite" color="primary" prepend-icon="mdi-plus" rounded="lg" @click="ouvrirCreation">
          Nouvel article
        </v-btn>
      </div>
    </div>

    <!-- En-tête d'impression : masquée à l'écran, visible uniquement sur le papier. -->
    <div class="etat-print-header">
      <div class="etat-print-header__brand">
        <div class="etat-print-header__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-finance" size="16" color="white" />
        </div>
        <div>
          <span class="etat-print-header__company">{{ parametresStore.parametres.nom }}</span>
          <span class="etat-print-header__doc">Catalogue des articles</span>
          <span class="etat-print-header__service">{{ resumeFiltres }}</span>
        </div>
      </div>
      <div class="etat-print-header__meta">
        <span>Imprimé le : {{ dateImpression }}</span>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-card class="classroom-card pa-4 mb-4 no-print">
      <v-row align="center" dense>
        <v-col cols="12" md="5">
          <v-text-field v-model="filtreRecherche" label="Rechercher (code, libellé)" variant="outlined"
            density="comfortable" hide-details prepend-inner-icon="mdi-magnify" clearable />
        </v-col>
        <v-col cols="6" md="4">
          <v-select v-model="filtreType" :items="[{ title: 'Tous les types', value: 'TOUS' }, ...typesPresents]"
            label="Type" variant="outlined" density="comfortable" hide-details />
        </v-col>
        <v-col cols="6" md="3">
          <v-select v-model="filtreStatut" :items="[
            { title: 'Tous statuts', value: 'TOUS' },
            { title: 'Actifs', value: 'ACTIFS' },
            { title: 'Inactifs', value: 'INACTIFS' },
          ]" label="Statut" variant="outlined" density="comfortable" hide-details />
        </v-col>
      </v-row>
    </v-card>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Code', key: 'code' },
          { title: 'Libellé', key: 'libelle' },
          { title: 'Type', key: 'type' },
          { title: 'Unité', key: 'uniteMesure' },
          { title: 'Prix de vente', key: 'prixVente', align: 'end' },
          { title: 'TVA', key: 'soumisTva', align: 'center' },
          { title: 'Compte produit', key: 'compteProduitNumero' },
          { title: 'Actif', key: 'actif' },
          { title: '', key: 'actions', sortable: false },
        ]"
        :items="articlesFiltres"
        :loading="loading"
        :items-per-page="lignesParPage"
      >
        <template #item.type="{ item }">
          <v-chip :color="META_TYPE[item.type]?.couleur ?? 'grey'" size="small" variant="tonal">
            {{ META_TYPE[item.type]?.label ?? item.type }}
          </v-chip>
          <v-chip v-if="item.minerais" color="amber-darken-3" size="x-small" variant="tonal" class="ml-1">
            Minerais
          </v-chip>
        </template>
        <template #item.prixVente="{ item }">
          <span :class="item.prixVente ? 'font-weight-medium' : 'text-medium-emphasis'">
            {{ fmtUSD(item.prixVente) }}
          </span>
        </template>
        <template #item.soumisTva="{ item }">
          <v-chip :color="item.soumisTva ? 'primary' : 'grey'" size="x-small" variant="tonal">
            {{ item.soumisTva ? 'Soumis' : 'Exonéré' }}
          </v-chip>
        </template>
        <template #item.compteProduitNumero="{ item }">
          <code v-if="item.compteProduitNumero" class="text-caption">{{ item.compteProduitNumero }}</code>
          <v-tooltip v-else text="Sans compte de produit, l'article ne peut pas être vendu" location="top">
            <template #activator="{ props }">
              <v-icon v-bind="props" icon="mdi-alert-outline" size="16" color="warning" />
            </template>
          </v-tooltip>
        </template>
        <template #item.actif="{ item }">
          <v-chip :color="item.actif ? 'success' : 'grey'" size="small" variant="flat">
            {{ item.actif ? 'Oui' : 'Non' }}
          </v-chip>
        </template>
        <template #item.actions="{ item }">
          <v-btn v-if="canWrite" class="no-print" size="small" variant="text" icon="mdi-pencil" @click="ouvrirEdition(item)" />
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="560" scrollable>
      <v-card class="pa-6">
        <h2 class="text-h6 mb-4">{{ editId ? 'Modifier' : 'Nouvel' }} article</h2>

        <v-alert
          v-if="typeExterne"
          type="info"
          variant="tonal"
          density="compact"
          rounded="lg"
          class="mb-4"
        >
          Article de type « {{ META_TYPE[form.type]?.label }} » : il se crée et se modifie depuis le module Restaurant,
          avec ses comptes d'imputation propres. Le type n'est pas modifiable ici.
        </v-alert>
        <div v-else class="type-switch mb-4">
          <button
            v-for="t in [
              { value: 'MARCHANDISE', label: 'Marchandise', icon: 'mdi-package-variant-closed' },
              { value: 'SERVICE', label: 'Service', icon: 'mdi-hand-extended-outline' },
              { value: 'CONSOMMABLE', label: 'Consommable', icon: 'mdi-package-variant' },
            ]"
            :key="t.value"
            type="button"
            class="type-switch__btn"
            :class="{ 'type-switch__btn--active': form.type === t.value }"
            @click="form.type = t.value as typeof form.type"
          >
            <v-icon :icon="t.icon" size="17" />
            {{ t.label }}
          </button>
        </div>

        <!-- Minerais : suivi camion par camion, chaque chargement ayant son
             propre prix de vente (voir Logistique > Camions de minerais). -->
        <div v-if="!estService && !typeExterne" class="minerai-toggle mb-4">
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

        <v-text-field v-model="form.code" label="Code" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.libelle" label="Libellé" variant="outlined" density="comfortable" class="mb-3" />
        <v-text-field v-model="form.uniteMesure" label="Unité de mesure" variant="outlined" density="comfortable" class="mb-3" />

        <!-- ── Achat (facultatif) ────────────────────────────────── -->
        <!-- Masqué pour un minerais : son prix d'achat n'est pas une propriété
             de l'article mais du chargement, saisi camion par camion à la
             réception (voir Camions de minerais). Le laisser visible ici
             laissait croire qu'il servait, alors qu'aucune écriture ne le lit. -->
        <template v-if="!estService && !form.minerais">
          <p class="art-section">Achat</p>
          <v-text-field
            v-model.number="form.prixAchatUSD"
            label="Prix d'achat HT (USD)"
            type="number"
            min="0"
            step="0.01"
            prepend-inner-icon="mdi-cart-outline"
            variant="outlined"
            density="comfortable"
            :hint="form.prixAchatUSD ? `≈ ${new Intl.NumberFormat('fr-FR').format(Math.round(form.prixAchatUSD * tauxChange))} FC` : 'Facultatif : prérempli à l\'achat en caisse, corrigeable au prix réellement payé'"
            persistent-hint
            class="mb-3"
          />
        </template>

        <!-- ── Vente ─────────────────────────────────────────────── -->
        <p class="art-section">Vente</p>
        <v-text-field
          v-model.number="form.prixVenteUSD"
          label="Prix de vente HT (USD)"
          type="number"
          min="0"
          step="0.01"
          prepend-inner-icon="mdi-currency-usd"
          variant="outlined"
          density="comfortable"
          :hint="form.prixVenteUSD ? `≈ ${new Intl.NumberFormat('fr-FR').format(Math.round(form.prixVenteUSD * tauxChange))} FC` : 'Sans prix, l\'article ne peut pas être vendu'"
          persistent-hint
          class="mb-3"
        />
        <ComptabiliteSelecteurCompte
          v-model="form.compteProduitNumero"
          :label="estService ? 'Compte de produit (ex. 7061)' : 'Compte de produit (ex. 7011)'"
          class="mb-1"
        />
        <v-switch v-model="form.soumisTva" label="Soumis à la TVA" color="primary" density="compact" class="mb-2" />

        <!-- ── Stock (marchandise uniquement) ────────────────────── -->
        <template v-if="!estService">
          <p class="art-section">Stock</p>
          <v-alert type="info" variant="tonal" density="compact" class="mb-3">
            La quantité en stock s'ajoute depuis
            <NuxtLink to="/logistique/stock" class="text-primary">État du stock</NuxtLink>,
            une fois l'article créé.
          </v-alert>
          <ComptabiliteSelecteurCompte v-model="form.compteStockNumero" label="Compte de stock (ex. 3111)" class="mb-3" />
          <!-- 6031 et non 6012 : V23 a réaffecté 6012 en « Achats de
               marchandises hors Région », le déstockage passe par 6031. -->
          <ComptabiliteSelecteurCompte v-model="form.compteChargeNumero" label="Compte de charge (ex. 6031)" class="mb-3" />
          <ComptabiliteSelecteurCompte v-model="form.compteAchatNumero" label="Compte d'achat (ex. 6011)" class="mb-1" />
          <p class="text-caption text-medium-emphasis mb-3">
            Débité au règlement d'une note de frais d'achat de cette marchandise (voir « Note de frais »).
          </p>
          <v-text-field v-model.number="form.stockMin" label="Stock minimum" type="number" variant="outlined" density="comfortable" class="mb-3" />
        </template>
        <v-alert v-else type="info" variant="tonal" density="compact" class="mb-3">
          Un service n'est pas stocké : ni entrepôt, ni coût des ventes.
        </v-alert>

        <v-switch v-model="form.actif" label="Actif" color="primary" />

        <div class="d-flex justify-end ga-3 mt-2">
          <v-btn variant="text" @click="dialog = false">Annuler</v-btn>
          <v-btn color="primary" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.art-section {
  font-size: 0.72rem;
  font-weight: 700;
  color: #9ca3af;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 4px 0 10px;
}

/* Selecteur de type d'article : piste grise avec pastille blanche active
   (type "iOS"), plus lisible que l'ancien v-btn-toggle a plat borde. */
.type-switch {
  display: flex;
  gap: 4px;
  padding: 4px;
  background: #f3f4f6;
  border-radius: 12px;
}
.type-switch__btn {
  flex: 1 1 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 9px 10px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: #6b7280;
  font-size: 0.84rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, box-shadow 0.15s;
}
.type-switch__btn:hover { color: #374151; }
.minerai-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 14px;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  background: #fffbeb;
}
.minerai-toggle__texte { display: flex; flex-direction: column; gap: 2px; }
.minerai-toggle__titre { font-size: 0.9rem; font-weight: 700; color: #92400e; display: flex; align-items: center; }
.minerai-toggle__desc { font-size: 0.75rem; color: #a16207; line-height: 1.35; }

.type-switch__btn--active {
  background: #fff;
  color: #16a34a;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.12);
}
</style>
