<script setup lang="ts">
import { useSessionStore } from '~/stores/session'
import { STATUTS, dateCourte, dateHeure } from '~/utils/libelles'

useHead({ title: 'Fiche client' })

interface Fiche {
  client: { id: number, nom: string, prenom: string | null, telephone: string | null, email: string | null, nationalite: string | null,
    dateNaissance: string | null, pieceType: string | null, pieceNumero: string | null, adresse: string | null, entreprise: string | null,
    preferences: string | null, notes: string | null, finappClientId: number | null, sejours: number }
  sejours: { id: number, reference: string, debut: string, fin: string, unite: string, statut: string, venteReference: string | null }[]
  pieces: { id: number, nomOrigine: string, typeContenu: string, creeLe: string, creePar: string }[]
}

const api = useApi()
const session = useSessionStore()
const route = useRoute()
const id = Number(route.params.id)
const fiche = ref<Fiche | null>(null)
const form = reactive<Record<string, any>>({})
const erreur = ref('')
const succes = ref('')
const action = ref('')
const fichier = ref<HTMLInputElement | null>(null)

async function charger() {
  try {
    fiche.value = await api<Fiche>(`/clients/${id}`)
    Object.assign(form, fiche.value.client)
  } catch (e) {
    erreur.value = messageErreur(e, 'Client introuvable.')
  }
}
onMounted(charger)

async function enregistrer() {
  action.value = 'enregistrer'
  erreur.value = ''
  try {
    await api(`/clients/${id}`, { method: 'PUT', body: {
      nom: form.nom, prenom: form.prenom || null, telephone: form.telephone || null, email: form.email || null, nationalite: form.nationalite || null,
      dateNaissance: form.dateNaissance || null, pieceType: form.pieceType || null, pieceNumero: form.pieceNumero || null, adresse: form.adresse || null,
      entreprise: form.entreprise || null, preferences: form.preferences || null, notes: form.notes || null,
    } })
    succes.value = 'Fiche enregistrée.'
    await charger()
  } catch (e) {
    erreur.value = messageErreur(e)
  } finally {
    action.value = ''
  }
}

async function lier() {
  action.value = 'lier'
  try {
    await api(`/clients/${id}/lier-finapp`, { method: 'POST' })
    succes.value = 'Client créé dans M-FINAPP (compte client propre) : ses séjours peuvent être facturés.'
    await charger()
  } catch (e) {
    erreur.value = messageErreur(e)
  } finally {
    action.value = ''
  }
}

async function envoyerPiece(ev: Event) {
  const f = (ev.target as HTMLInputElement).files?.[0]
  if (!f) return
  action.value = 'piece'
  try {
    const donnees = new FormData()
    donnees.append('fichier', f)
    await api(`/clients/${id}/pieces`, { method: 'POST', body: donnees })
    succes.value = "Pièce d'identité enregistrée."
    await charger()
  } catch (e) {
    erreur.value = messageErreur(e)
  } finally {
    action.value = ''
    if (fichier.value) fichier.value.value = ''
  }
}

async function voirPiece(pieceId: number) {
  const onglet = window.open('', '_blank')
  try {
    const blob = await api<Blob>(`/clients/${id}/pieces/${pieceId}`, { responseType: 'blob' })
    const url = URL.createObjectURL(blob)
    if (onglet) onglet.location.href = url
    setTimeout(() => URL.revokeObjectURL(url), 60_000)
  } catch (e) {
    onglet?.close()
    erreur.value = messageErreur(e)
  }
}
</script>

<template>
  <div v-if="fiche">
    <div class="page-head">
      <div>
        <h1 class="page-title">{{ fiche.client.prenom ? fiche.client.prenom + ' ' : '' }}{{ fiche.client.nom }}</h1>
        <p class="page-sub">{{ fiche.client.sejours }} séjour(s)<template v-if="fiche.client.entreprise"> · {{ fiche.client.entreprise }}</template></p>
      </div>
      <div class="d-flex ga-2">
        <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/clients">Clients</v-btn>
        <v-btn v-if="!fiche.client.finappClientId && session.a('RECEPTIONNISTE', 'DIR_HEBERGEMENT')" variant="tonal" prepend-icon="mdi-link-variant"
          :loading="action === 'lier'" @click="lier">Lier à M-FINAPP</v-btn>
        <v-chip v-else-if="fiche.client.finappClientId" color="primary" variant="tonal" prepend-icon="mdi-link-variant">Lié à M-FINAPP</v-chip>
      </div>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-4" @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" closable class="mb-4" @click:close="succes = ''">{{ succes }}</v-alert>

    <v-row>
      <v-col cols="12" md="7">
        <v-card class="classroom-card pa-5">
          <div class="kpi-label mb-3">Identité</div>
          <v-row dense>
            <v-col cols="12" sm="6"><v-text-field v-model="form.nom" label="Nom *" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="form.prenom" label="Prénom" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="form.telephone" label="Téléphone" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="form.email" label="E-mail" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="form.nationalite" label="Nationalité" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="form.dateNaissance" type="date" label="Date de naissance" /></v-col>
            <v-col cols="12" sm="6"><v-select v-model="form.pieceType" :items="['Carte d\'électeur', 'Passeport', 'Permis de conduire', 'Carte de séjour', 'Autre']" label="Pièce d'identité" clearable /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="form.pieceNumero" label="Numéro de la pièce" /></v-col>
            <v-col cols="12"><v-text-field v-model="form.adresse" label="Adresse" /></v-col>
            <v-col cols="12"><v-text-field v-model="form.entreprise" label="Entreprise (séjours facturés)" /></v-col>
            <v-col cols="12"><v-textarea v-model="form.preferences" label="Préférences (étage, oreiller, régime…)" rows="2" auto-grow /></v-col>
            <v-col cols="12"><v-textarea v-model="form.notes" label="Notes internes" rows="2" auto-grow /></v-col>
          </v-row>
          <v-btn color="primary" :loading="action === 'enregistrer'" prepend-icon="mdi-content-save-outline" @click="enregistrer">Enregistrer</v-btn>
        </v-card>
      </v-col>
      <v-col cols="12" md="5">
        <v-card class="classroom-card pa-5 mb-4">
          <div class="d-flex align-center mb-3">
            <div class="kpi-label">Pièces d'identité</div>
            <v-spacer />
            <v-btn size="small" variant="tonal" prepend-icon="mdi-camera-outline" :loading="action === 'piece'" @click="fichier?.click()">Ajouter</v-btn>
            <input ref="fichier" type="file" accept="image/*,application/pdf" capture="environment" hidden @change="envoyerPiece">
          </div>
          <v-list v-if="fiche.pieces.length" density="compact">
            <v-list-item v-for="p in fiche.pieces" :key="p.id" prepend-icon="mdi-card-account-details-outline" :title="p.nomOrigine || 'Pièce'"
              :subtitle="`${dateHeure(p.creeLe)} · ${p.creePar}`" @click="voirPiece(p.id)" />
          </v-list>
          <p v-else class="text-body-2 text-medium-emphasis mb-0">Aucune pièce : photographiez-la à l'arrivée.</p>
        </v-card>
        <v-card class="classroom-card">
          <v-card-title class="text-subtitle-1 font-weight-bold">Séjours</v-card-title>
          <v-list v-if="fiche.sejours.length" density="compact">
            <v-list-item v-for="s in fiche.sejours" :key="s.id" :to="`/reservations/${s.id}`">
              <v-list-item-title>{{ dateCourte(s.debut) }} → {{ dateCourte(s.fin) }} · unité {{ s.unite }}</v-list-item-title>
              <v-list-item-subtitle>{{ s.reference }}<template v-if="s.venteReference"> · vente {{ s.venteReference }}</template></v-list-item-subtitle>
              <template #append><span class="pastille" :style="{ '--c': STATUTS[s.statut]?.couleur }">{{ STATUTS[s.statut]?.libelle }}</span></template>
            </v-list-item>
          </v-list>
          <v-card-text v-else class="text-medium-emphasis">Aucun séjour.</v-card-text>
        </v-card>
      </v-col>
    </v-row>
  </div>
  <v-alert v-else-if="erreur" type="error" variant="tonal">{{ erreur }}</v-alert>
</template>
