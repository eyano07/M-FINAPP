<script setup lang="ts">
import type { LigneMouvementForm } from '~/components/logistique/LignesMouvement.vue'

// Creation d'un mouvement : reservee a LOGISTIQUE (ADMIN ne modifie que les
// pages dediees a l'administration).
definePageMeta({ module: 'LOGISTIQUE', niveau: 'ECRITURE', roles: ['LOGISTIQUE'] })

const api = useApi()
const router = useRouter()
const saving = ref(false)
const erreur = ref('')

const types = [
  { title: 'Entrée', value: 'ENTREE' },
  { title: 'Sortie', value: 'SORTIE' },
  { title: 'Transfert', value: 'TRANSFERT' },
]

const form = reactive({
  type: 'ENTREE' as 'ENTREE' | 'SORTIE' | 'TRANSFERT',
  dateMouvement: new Date().toISOString().slice(0, 10),
  libelle: '',
  compteContrepartieNumero: '' as string | null,
  lignes: [
    { articleId: null, entrepotSourceId: null, entrepotCibleId: null, quantite: null, coutUnitaire: null },
  ] as LigneMouvementForm[],
})

const besoinContrepartie = computed(() => form.type === 'ENTREE')

async function enregistrer() {
  if (form.lignes.some(l => !l.articleId || !l.quantite || l.quantite <= 0)) {
    erreur.value = 'Chaque ligne doit avoir un article et une quantité positive.'
    return
  }
  if (besoinContrepartie.value && !form.compteContrepartieNumero) {
    erreur.value = 'Une entrée nécessite un compte de contrepartie.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    await api('/logistique/mouvements', {
      method: 'POST',
      body: {
        type: form.type,
        dateMouvement: form.dateMouvement,
        libelle: form.libelle,
        compteContrepartieNumero: form.compteContrepartieNumero || null,
        lignes: form.lignes.map(l => ({
          articleId: l.articleId,
          entrepotSourceId: l.entrepotSourceId,
          entrepotCibleId: l.entrepotCibleId,
          quantite: l.quantite,
          coutUnitaire: l.coutUnitaire || 0,
        })),
      },
    })
    router.push('/logistique/mouvements')
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Enregistrement impossible.'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Nouveau mouvement de stock</h1>
        <p class="page-sub">Entrée, sortie ou transfert d'articles</p>
      </div>
      <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/logistique/mouvements">Retour</v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-card class="classroom-card pa-6 mb-4">
      <v-row>
        <v-col cols="12" md="3">
          <v-select v-model="form.type" :items="types" label="Type" variant="outlined" density="comfortable" />
        </v-col>
        <v-col cols="12" md="3">
          <v-text-field v-model="form.dateMouvement" label="Date" type="date" variant="outlined" density="comfortable" />
        </v-col>
        <v-col cols="12" md="3">
          <v-text-field v-model="form.libelle" label="Libellé" variant="outlined" density="comfortable" />
        </v-col>
        <v-col v-if="besoinContrepartie" cols="12" md="3">
          <ComptabiliteSelecteurCompte v-model="form.compteContrepartieNumero" label="Compte contrepartie (ex. 401)" />
        </v-col>
      </v-row>
    </v-card>

    <v-card class="classroom-card pa-6 mb-4">
      <LogistiqueLignesMouvement v-model="form.lignes" :type="form.type" />
    </v-card>

    <div class="d-flex justify-end ga-3">
      <v-btn variant="outlined" to="/logistique/mouvements">Annuler</v-btn>
      <v-btn color="primary" :loading="saving" prepend-icon="mdi-content-save" @click="enregistrer">
        Enregistrer (brouillon)
      </v-btn>
    </div>
  </div>
</template>
