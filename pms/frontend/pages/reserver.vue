<script setup lang="ts">
/**
 * Moteur de réservation direct (page publique, sans connexion, sans commission) : le visiteur choisit l'établissement,
 * ses dates et le nombre de personnes, voit les types d'unité libres avec leur prix, et envoie une demande. La réception
 * la confirme (acompte) ; sans confirmation sous 48 h elle est libérée. À mettre en lien sur le site web de l'entreprise.
 */
definePageMeta({ layout: false })
useHead({ title: 'Réserver' })

interface Etab { id: number, nom: string, type: string, adresse: string | null, telephone: string | null, heureArrivee: string, heureDepart: string }
interface Offre { typeId: number, type: string, description: string | null, capacite: number, prixNuit: number, nuits: number, totalHt: number, disponibles: number }

const base = useRuntimeConfig().public.apiBase as string
const jour = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const demain = new Date(Date.now() + 86_400_000)
const apres = new Date(Date.now() + 2 * 86_400_000)

const etablissements = ref<Etab[]>([])
const recherche = reactive({ etablissementId: null as number | null, arrivee: jour(demain), depart: jour(apres), adultes: 2, enfants: 0 })
const offres = ref<Offre[] | null>(null)
const choix = ref<Offre | null>(null)
const client = reactive({ nom: '', prenom: '', telephone: '', email: '', message: '', site: '' })
const chargement = ref(false)
const envoi = ref(false)
const erreur = ref('')
const accuse = ref<{ reference: string, message: string } | null>(null)

onMounted(async () => {
  try {
    etablissements.value = await $fetch<Etab[]>(`${base}/public/etablissements`)
    if (etablissements.value.length === 1) recherche.etablissementId = etablissements.value[0].id
  } catch (e) {
    erreur.value = messageErreur(e, 'La réservation en ligne est momentanément indisponible.')
  }
})

const etab = computed(() => etablissements.value.find(e => e.id === recherche.etablissementId))
const usd = (v: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(v) || 0)

async function chercher() {
  erreur.value = ''
  choix.value = null
  accuse.value = null
  if (!recherche.etablissementId) { erreur.value = "Choisissez l'établissement."; return }
  chargement.value = true
  try {
    offres.value = await $fetch<Offre[]>(`${base}/public/disponibilites`, { query: {
      etablissementId: recherche.etablissementId, arrivee: recherche.arrivee, depart: recherche.depart,
      personnes: recherche.adultes + recherche.enfants,
    } })
  } catch (e) {
    offres.value = null
    erreur.value = messageErreur(e, 'Recherche impossible.')
  } finally {
    chargement.value = false
  }
}

async function demander() {
  if (!choix.value) return
  erreur.value = ''
  envoi.value = true
  try {
    accuse.value = await $fetch(`${base}/public/demandes`, { method: 'POST', body: {
      etablissementId: recherche.etablissementId, typeId: choix.value.typeId, arrivee: recherche.arrivee, depart: recherche.depart,
      adultes: recherche.adultes, enfants: recherche.enfants, nom: client.nom, prenom: client.prenom || null, telephone: client.telephone,
      email: client.email || null, message: client.message || null, site: client.site,
    } })
  } catch (e) {
    erreur.value = messageErreur(e, "La demande n'a pas pu être envoyée.")
  } finally {
    envoi.value = false
  }
}
</script>

<template>
  <div class="public">
    <header class="public__entete">
      <div class="public__barre">
        <span class="public__logo logo-principal"><LogoMfinapp /></span>
        <div>
          <div class="public__titre">{{ etab?.nom ?? 'Réservation en ligne' }}</div>
          <div class="public__sous">{{ etab?.adresse ?? 'Hôtel · motel · appartements' }}</div>
        </div>
        <v-spacer />
        <a v-if="etab?.telephone" :href="`tel:${etab.telephone}`" class="public__tel"><v-icon icon="mdi-phone-outline" size="16" />{{ etab.telephone }}</a>
      </div>
      <div class="public__accroche">
        <h1>Réservez votre séjour en direct</h1>
        <p>Meilleur prix, sans commission : choisissez vos dates, nous confirmons votre réservation rapidement.</p>
      </div>
    </header>

    <main class="public__contenu">
      <v-card class="pa-5 mb-6 public__recherche" rounded="xl">
        <h2 class="text-h6 font-weight-bold mb-4">Vos dates</h2>
        <v-row dense>
          <v-col v-if="etablissements.length > 1" cols="12">
            <v-select v-model="recherche.etablissementId" :items="etablissements" item-title="nom" item-value="id" label="Établissement" variant="outlined" density="comfortable" />
          </v-col>
          <v-col cols="6" md="3"><v-text-field v-model="recherche.arrivee" type="date" label="Arrivée" variant="outlined" density="comfortable" /></v-col>
          <v-col cols="6" md="3"><v-text-field v-model="recherche.depart" type="date" label="Départ" variant="outlined" density="comfortable" /></v-col>
          <v-col cols="6" md="2"><v-text-field v-model.number="recherche.adultes" type="number" min="1" label="Adultes" variant="outlined" density="comfortable" /></v-col>
          <v-col cols="6" md="2"><v-text-field v-model.number="recherche.enfants" type="number" min="0" label="Enfants" variant="outlined" density="comfortable" /></v-col>
          <v-col cols="12" md="2"><v-btn color="primary" size="large" block :loading="chargement" prepend-icon="mdi-magnify" @click="chercher">Voir</v-btn></v-col>
        </v-row>
        <p v-if="etab" class="text-caption text-medium-emphasis mb-0">Arrivée à partir de {{ etab.heureArrivee.slice(0, 5) }}, départ avant {{ etab.heureDepart.slice(0, 5) }}.</p>
      </v-card>

      <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

      <v-alert v-if="accuse" type="success" variant="tonal" class="mb-4">
        <div class="font-weight-bold mb-1">Demande {{ accuse.reference }} envoyée</div>{{ accuse.message }}
      </v-alert>

      <template v-else-if="offres">
        <v-alert v-if="!offres.length" type="info" variant="tonal" class="mb-4">
          Aucune disponibilité à ces dates pour {{ recherche.adultes + recherche.enfants }} personne(s).
          <template v-if="etab?.telephone"> Appelez-nous au {{ etab.telephone }}.</template>
        </v-alert>
        <div class="offres">
          <v-card v-for="o in offres" :key="o.typeId" class="pa-5 offre" rounded="xl" :class="{ 'offre--choisie': choix?.typeId === o.typeId }" @click="choix = o">
            <div class="d-flex align-center">
              <div class="text-subtitle-1 font-weight-bold">{{ o.type }}</div>
              <v-spacer /><v-icon v-if="choix?.typeId === o.typeId" icon="mdi-check-circle" color="primary" />
            </div>
            <div class="text-body-2 text-medium-emphasis mb-3">{{ o.description || `Jusqu'à ${o.capacite} personne(s)` }}</div>
            <div class="text-h6 font-weight-bold">{{ usd(o.totalHt) }}</div>
            <div class="text-caption text-medium-emphasis">{{ o.nuits }} nuit(s) × {{ usd(o.prixNuit) }} · hors taxes</div>
            <div v-if="o.disponibles <= 2" class="text-caption text-warning mt-1">Plus que {{ o.disponibles }} disponible(s)</div>
          </v-card>
        </div>

        <v-card v-if="choix" class="pa-5 mt-4" rounded="xl">
          <h2 class="text-h6 font-weight-bold mb-4">Vos coordonnées</h2>
          <v-row dense>
            <v-col cols="12" sm="6"><v-text-field v-model="client.nom" label="Nom *" variant="outlined" density="comfortable" autocomplete="family-name" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="client.prenom" label="Prénom" variant="outlined" density="comfortable" autocomplete="given-name" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="client.telephone" label="Téléphone *" inputmode="tel" variant="outlined" density="comfortable" autocomplete="tel" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="client.email" label="E-mail (pour la confirmation)" type="email" variant="outlined" density="comfortable" autocomplete="email" /></v-col>
            <v-col cols="12"><v-textarea v-model="client.message" label="Message (heure d'arrivée, demande particulière…)" rows="2" auto-grow variant="outlined" density="comfortable" /></v-col>
          </v-row>
          <!-- Champ piège pour les robots : invisible pour un humain. -->
          <input v-model="client.site" type="text" name="site" tabindex="-1" autocomplete="off" class="piege" aria-hidden="true">
          <p class="text-caption text-medium-emphasis">La réception vous contacte pour confirmer ; un acompte peut être demandé. Sans confirmation sous 48 heures,
            la demande est libérée.</p>
          <v-btn color="primary" size="large" :loading="envoi" :disabled="!client.nom.trim() || !client.telephone.trim()" prepend-icon="mdi-send" @click="demander">
            Envoyer ma demande
          </v-btn>
        </v-card>
      </template>
    </main>
  </div>
</template>

<style scoped>
.public { min-height: 100dvh; background: var(--d-aurore), var(--d-canvas); }
.public__entete {
  position: relative; overflow: hidden; isolation: isolate; color: #fff; background: var(--d-grad-hero);
  padding: 18px clamp(16px, 4vw, 40px) 96px; margin: 12px; border-radius: 30px;
}
.public__entete::before {
  content: ''; position: absolute; inset: 0; z-index: -1; opacity: 0.55;
  background-image: linear-gradient(rgba(255, 255, 255, 0.07) 1px, transparent 1px), linear-gradient(90deg, rgba(255, 255, 255, 0.07) 1px, transparent 1px);
  background-size: 36px 36px; mask-image: radial-gradient(ellipse 70% 90% at 75% 20%, #000 10%, transparent 70%);
}
.public__barre { display: flex; align-items: center; gap: 14px; max-width: 1000px; margin: 0 auto; }
.public__logo { width: 44px; height: 44px; border-radius: 14px; display: grid; place-items: center; padding: 8px !important; color: #fff !important; background: rgba(255,255,255,.14) !important; border: 1px solid rgba(255,255,255,.2) !important; }
.public__titre { font-weight: 800; font-size: 1.1rem; letter-spacing: -0.02em; }
.public__sous { font-size: 0.85rem; opacity: 0.8; }
.public__tel { display: inline-flex; align-items: center; gap: 6px; color: #fff; text-decoration: none; font-weight: 600; font-size: 0.88rem;
  padding: 8px 14px; border-radius: 999px; background: rgba(255,255,255,.12); border: 1px solid rgba(255,255,255,.2); }
.public__accroche { max-width: 1000px; margin: 46px auto 0; }
.public__accroche h1 { font-size: clamp(1.9rem, 4.6vw, 3.2rem); font-weight: 780; letter-spacing: -0.045em; line-height: 1.04; margin: 0 0 12px; max-width: 640px; }
.public__accroche p { font-size: 1.02rem; opacity: 0.85; margin: 0; max-width: 560px; }
.public__contenu { max-width: 1024px; margin: -72px auto 0; padding: 0 16px 56px; position: relative; }
.public__recherche { box-shadow: var(--d-shadow-3) !important; border: 1px solid var(--d-line) !important; }
.offres { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 14px; }
.offre { cursor: pointer; border: 1px solid var(--d-line) !important; box-shadow: var(--d-shadow-1) !important; transition: transform .2s, box-shadow .2s; }
.offre:hover { transform: translateY(-3px); box-shadow: var(--d-shadow-3) !important; }
.offre--choisie { box-shadow: var(--d-shadow-2), inset 0 0 0 2px var(--color-primary) !important; }
.piege { position: absolute; left: -9999px; width: 1px; height: 1px; opacity: 0; }
</style>
