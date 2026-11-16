<script setup lang="ts">
// Le caissier a LOGISTIQUE en LECTURE pour charger le catalogue depuis
// « Nouvelle vente » (V29), pas pour consulter les ecrans du domaine : la
// garde par roles l'exclut sans retirer la permission de module.
definePageMeta({ module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'GEST_PATRIMOINE', 'RESP_CUISINE', 'RESP_BAR', 'ADMIN'] })

import type { ArticleCatalogue } from '~/components/logistique/FormulaireArticle.vue'

// 16/11/2026 : cette page ne montre plus que les ARTICLES À VENDRE (marchandises stockées, minerais compris). Les
// services vendables ont leur page (Logistique › Services), les consommables la leur (Patrimoine › Consommables),
// la carte du restaurant (plats, boissons, provisions) reste au module Restaurant et les biens durables de
// l'entreprise (immobilier, matériel roulant…) sont des immobilisations (Patrimoine › Immobilisations).
type Article = ArticleCatalogue & { vendable: boolean }

const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()
const loading = ref(false)
const erreur = ref('')
const articles = ref<Article[]>([])
const tauxChange = ref(1)
const formulaire = ref<{ ouvrir: (a?: ArticleCatalogue) => void } | null>(null)

const canWrite = computed(() => auth.hasAnyRole(['LOGISTIQUE', 'DFIN']))

// ── Filtres (écran + impression, qui n'imprime que le résultat filtré) ────
const filtreRecherche = ref('')
const filtreStatut = ref<'TOUS' | 'ACTIFS' | 'INACTIFS'>('TOUS')

const articlesFiltres = computed(() => {
  const q = filtreRecherche.value.trim().toLowerCase()
  return articles.value.filter((a) => {
    if (q && !a.code.toLowerCase().includes(q) && !a.libelle.toLowerCase().includes(q)) return false
    if (filtreStatut.value === 'ACTIFS' && !a.actif) return false
    if (filtreStatut.value === 'INACTIFS' && a.actif) return false
    return true
  })
})

const resumeFiltres = computed(() => {
  const parts: string[] = []
  if (filtreStatut.value !== 'TOUS') parts.push(filtreStatut.value === 'ACTIFS' ? 'Actifs' : 'Inactifs')
  if (filtreRecherche.value.trim()) parts.push(`« ${filtreRecherche.value.trim()} »`)
  return parts.length ? parts.join(' · ') : 'Tous les articles à vendre'
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
    articles.value = arts.filter(a => a.type === 'MARCHANDISE')
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
        <p class="page-sub">Marchandises stockées destinées à la vente · les services ont leur page, le patrimoine reste au module Patrimoine</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn v-if="articlesFiltres.length" color="error" variant="tonal" rounded="lg"
          prepend-icon="mdi-printer-outline" @click="imprimer">
          Imprimer
        </v-btn>
        <v-btn color="primary" variant="tonal" rounded="lg" prepend-icon="mdi-hand-extended-outline" to="/logistique/services">
          Services
        </v-btn>
        <v-btn v-if="canWrite" color="primary" prepend-icon="mdi-plus" rounded="lg" @click="formulaire?.ouvrir()">
          Nouvel article
        </v-btn>
      </div>
    </div>

    <!-- En-tête d'impression : masquée à l'écran, visible uniquement sur le papier. -->
    <div class="etat-print-header print-entete">
      <div class="etat-print-header__brand print-entete__marque">
        <div class="etat-print-header__logo print-entete__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-finance" size="16" color="white" />
        </div>
        <div>
          <span class="etat-print-header__company print-entete__nom">{{ parametresStore.parametres.nom }}</span>
          <span class="etat-print-header__doc">Articles à vendre</span>
          <span class="etat-print-header__service">{{ resumeFiltres }}</span>
        </div>
      </div>
      <div class="etat-print-header__meta print-entete__meta">
        <span>Imprimé le : {{ dateImpression }}</span>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-card class="classroom-card pa-4 mb-4 no-print">
      <v-row align="center" dense>
        <v-col cols="12" md="8">
          <v-text-field v-model="filtreRecherche" label="Rechercher (code, libellé)" variant="outlined"
            density="comfortable" hide-details prepend-inner-icon="mdi-magnify" clearable />
        </v-col>
        <v-col cols="12" md="4">
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
        <template #item.libelle="{ item }">
          {{ item.libelle }}
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
          <v-btn v-if="canWrite" class="no-print" size="small" variant="text" icon="mdi-pencil" @click="formulaire?.ouvrir(item)" />
        </template>
      </v-data-table>
    </v-card>

    <LogistiqueFormulaireArticle ref="formulaire" type="MARCHANDISE" :taux-change="tauxChange" @enregistre="charger" />
  </div>
</template>
