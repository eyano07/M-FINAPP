<script setup lang="ts">
import { useEtablissementStore } from '~/stores/etablissement'
import { useSessionStore } from '~/stores/session'
import { CATEGORIES_TICKET, PRIORITES, STATUTS_TICKET, dateHeure } from '~/utils/libelles'

/**
 * Terrain (téléphone) : la gouvernante voit ses chambres à faire, le technicien ses pannes. Un geste par étape
 * (Commencer, Terminé), et « Signaler une panne » avec la photo prise sur place.
 */
definePageMeta({ layout: 'terrain' })
useHead({ title: 'Terrain' })

interface Tache { id: number, uniteId: number, uniteNumero: string, type: string, statut: string, priorite: string, assigneNom: string | null, notes: string | null, creeLe: string }
interface Ticket { id: number, reference: string, uniteId: number | null, uniteNumero: string | null, lieu: string | null, categorie: string, priorite: string,
  titre: string, statut: string, horsService: boolean, assigneNom: string | null, signaleLe: string }

const api = useApi()
const session = useSessionStore()
const etablissements = useEtablissementStore()
const technicien = computed(() => session.a('TECHNICIEN', 'DIR_HEBERGEMENT'))
const menage = computed(() => session.a('GOUVERNANTE', 'RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN'))
const onglet = ref<'menage' | 'maintenance'>(session.a('TECHNICIEN') && !session.a('GOUVERNANTE') ? 'maintenance' : 'menage')
const taches = ref<Tache[]>([])
const tickets = ref<Ticket[]>([])
const erreur = ref('')
const enCours = ref<string | null>(null)

async function charger() {
  try {
    const query = { etablissementId: etablissements.filtre }
    const [t, k] = await Promise.all([
      menage.value ? api<Tache[]>('/menage/taches', { query }) : Promise.resolve([] as Tache[]),
      api<Ticket[]>('/maintenance/tickets', { query: { ...query, ouverts: true, moi: session.a('TECHNICIEN') && !session.a('DIR_HEBERGEMENT') } }),
    ])
    taches.value = t.filter(x => x.statut !== 'TERMINEE')
    tickets.value = k
    erreur.value = ''
  } catch (e) { erreur.value = messageErreur(e) }
}
let minuterie: ReturnType<typeof setInterval> | null = null
onMounted(() => { charger(); minuterie = setInterval(charger, 20_000) })
onBeforeUnmount(() => { if (minuterie) clearInterval(minuterie) })

async function agir(cle: string, f: () => Promise<unknown>) {
  enCours.value = cle
  try { await f(); await charger() } catch (e) { erreur.value = messageErreur(e) } finally { enCours.value = null }
}
const TYPES: Record<string, string> = { DEPART: 'Après départ', RECOUCHE: 'Recouche', INSPECTION: 'Inspection', AUTRE: 'À faire' }

// ── Panne signalée sur place, photo comprise ────────────────────────────────────────────────────────────────
const panne = reactive({ ouvert: false, uniteId: null as number | null, uniteNumero: '', categorie: 'PLOMBERIE', titre: '', urgente: false, photo: null as File | null, enCours: false, erreur: '' })
function signaler(t?: Tache) {
  Object.assign(panne, { ouvert: true, uniteId: t?.uniteId ?? null, uniteNumero: t?.uniteNumero ?? '', categorie: 'PLOMBERIE', titre: '', urgente: false, photo: null, enCours: false, erreur: '' })
}
const champPhoto = ref<HTMLInputElement | null>(null)
async function envoyerPanne() {
  panne.enCours = true
  panne.erreur = ''
  try {
    const t = await api<{ id: number }>('/maintenance/tickets', { method: 'POST', body: {
      uniteId: panne.uniteId, etablissementId: panne.uniteId ? null : (etablissements.filtre ?? etablissements.liste[0]?.id), lieu: panne.uniteId ? null : panne.uniteNumero || 'Parties communes',
      categorie: panne.categorie, priorite: panne.urgente ? 'URGENTE' : 'NORMALE', titre: panne.titre } })
    if (panne.photo) {
      const donnees = new FormData()
      donnees.append('fichier', panne.photo)
      await api(`/maintenance/tickets/${t.id}/photos`, { method: 'POST', body: donnees })
    }
    panne.ouvert = false
    await charger()
  } catch (e) { panne.erreur = messageErreur(e, "Le signalement n'est pas parti.") } finally { panne.enCours = false }
}
async function resoudre(k: Ticket) {
  const texte = await useDialogues().saisir({ titre: 'Qu’avez-vous fait ?', libelle: 'Intervention réalisée', bouton: 'Marquer résolu', obligatoire: true })
  if (texte) await agir(`k${k.id}`, () => api(`/maintenance/tickets/${k.id}/statut`, { method: 'POST', body: { statut: 'RESOLU', resolution: texte } }))
}
</script>

<template>
  <div>
    <div class="salut">
      <div class="salut__titre">Bonjour{{ session.utilisateur?.prenom ? `, ${session.utilisateur.prenom}` : '' }}</div>
      <div class="salut__sous">{{ taches.length }} chambre(s) à faire · {{ tickets.length }} panne(s) en cours</div>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-3" @click:close="erreur = ''">{{ erreur }}</v-alert>

    <div v-if="menage && technicien" class="bascule mb-4">
      <button type="button" :class="{ actif: onglet === 'menage' }" @click="onglet = 'menage'"><v-icon icon="mdi-broom" size="18" />Ménage <b>{{ taches.length }}</b></button>
      <button type="button" :class="{ actif: onglet === 'maintenance' }" @click="onglet = 'maintenance'"><v-icon icon="mdi-wrench-outline" size="18" />Pannes <b>{{ tickets.length }}</b></button>
    </div>

    <template v-if="onglet === 'menage' && menage">
      <article v-for="t in taches" :key="t.id" class="carte" :class="{ 'carte--urgente': t.priorite === 'HAUTE' }">
        <div class="carte__tete">
          <span class="carte__numero">{{ t.uniteNumero }}</span>
          <div class="carte__texte">
            <strong>{{ TYPES[t.type] ?? 'À faire' }}</strong>
            <small>{{ t.notes || dateHeure(t.creeLe) }}</small>
            <span v-if="t.priorite === 'HAUTE'" class="carte__urgent"><v-icon icon="mdi-flash" size="14" />Arrivée aujourd’hui</span>
          </div>
        </div>
        <div class="carte__actions">
          <v-btn v-if="t.statut === 'A_FAIRE'" size="x-large" variant="tonal" prepend-icon="mdi-play" :loading="enCours === `t${t.id}`"
            @click="agir(`t${t.id}`, () => api(`/menage/taches/${t.id}/commencer`, { method: 'POST' }))">Commencer</v-btn>
          <v-btn size="x-large" color="primary" variant="flat" prepend-icon="mdi-check" :loading="enCours === `t${t.id}`"
            @click="agir(`t${t.id}`, () => api(`/menage/taches/${t.id}/terminer`, { method: 'POST' }))">Terminé</v-btn>
          <v-btn size="x-large" icon="mdi-alert-plus-outline" variant="text" color="error" aria-label="Signaler une panne dans cette unité" @click="signaler(t)" />
        </div>
      </article>
      <div v-if="!taches.length" class="d-carte"><div class="d-vide"><div class="d-vide__icone"><v-icon icon="mdi-party-popper" size="28" /></div>
        <div class="d-vide__titre">Tout est fait</div><div class="d-vide__texte">Les nouvelles chambres à préparer apparaissent ici d’elles-mêmes.</div></div></div>
    </template>

    <template v-else>
      <article v-for="k in tickets" :key="k.id" class="carte" :class="{ 'carte--urgente': ['HAUTE', 'URGENTE'].includes(k.priorite) }">
        <div class="carte__tete">
          <span class="carte__numero carte__numero--icone" :style="{ '--c': PRIORITES[k.priorite]?.couleur }"><v-icon :icon="CATEGORIES_TICKET[k.categorie]?.icone" size="26" /></span>
          <div class="carte__texte">
            <strong>{{ k.titre }}</strong>
            <small>{{ k.uniteNumero ? `Unité ${k.uniteNumero}` : k.lieu }} · {{ k.reference }}</small>
            <span class="pastille mt-1" :style="{ '--c': STATUTS_TICKET[k.statut]?.couleur }">{{ STATUTS_TICKET[k.statut]?.libelle }}</span>
          </div>
        </div>
        <div v-if="technicien" class="carte__actions">
          <v-btn v-if="k.statut === 'OUVERT' || k.statut === 'ATTENTE_PIECES'" size="x-large" variant="tonal" prepend-icon="mdi-play" :loading="enCours === `k${k.id}`"
            @click="agir(`k${k.id}`, () => api(`/maintenance/tickets/${k.id}/statut`, { method: 'POST', body: { statut: 'EN_COURS' } }))">Commencer</v-btn>
          <v-btn v-if="k.statut !== 'OUVERT'" size="x-large" color="success" variant="flat" prepend-icon="mdi-check" :loading="enCours === `k${k.id}`" @click="resoudre(k)">Résolu</v-btn>
          <v-btn size="x-large" icon="mdi-open-in-new" variant="text" :to="'/maintenance'" aria-label="Ouvrir la maintenance" />
        </div>
      </article>
      <div v-if="!tickets.length" class="d-carte"><div class="d-vide"><div class="d-vide__icone"><v-icon icon="mdi-check-decagram-outline" size="28" /></div>
        <div class="d-vide__titre">Aucune panne en cours</div></div></div>
    </template>

    <button type="button" class="signaler" @click="signaler()"><v-icon icon="mdi-alert-plus-outline" size="22" />Signaler une panne</button>

    <v-dialog v-model="panne.ouvert" fullscreen transition="dialog-bottom-transition">
      <v-card class="panne">
        <div class="panne__tete">
          <v-btn icon="mdi-close" variant="text" aria-label="Fermer" @click="panne.ouvert = false" />
          <b>Signaler une panne{{ panne.uniteId ? ` · unité ${panne.uniteNumero}` : '' }}</b>
        </div>
        <div class="panne__corps">
          <v-alert v-if="panne.erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ panne.erreur }}</v-alert>
          <v-text-field v-if="!panne.uniteId" v-model="panne.uniteNumero" label="Où ? (unité, hall, piscine…)" />
          <div class="categories mb-4">
            <button v-for="(c, cle) in CATEGORIES_TICKET" :key="cle" type="button" :class="{ actif: panne.categorie === cle }" @click="panne.categorie = cle as string">
              <v-icon :icon="c.icone" size="24" /><span>{{ c.libelle }}</span></button>
          </div>
          <v-text-field v-model="panne.titre" label="Le problème en quelques mots *" />
          <button type="button" class="photo" @click="champPhoto?.click()">
            <v-icon :icon="panne.photo ? 'mdi-check-circle' : 'mdi-camera-outline'" size="28" />
            <span>{{ panne.photo ? 'Photo prise — la changer' : 'Prendre une photo' }}</span>
          </button>
          <input ref="champPhoto" type="file" accept="image/*" capture="environment" hidden @change="(e: Event) => panne.photo = (e.target as HTMLInputElement).files?.[0] ?? null">
          <v-switch v-model="panne.urgente" color="error" inset hide-details label="C’est urgent" class="mt-3" />
          <v-btn color="primary" variant="flat" size="x-large" block class="mt-4" :loading="panne.enCours" :disabled="!panne.titre.trim() || (!panne.uniteId && !panne.uniteNumero.trim())"
            prepend-icon="mdi-send" @click="envoyerPanne">Envoyer au technicien</v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.salut { margin: 4px 2px 16px; }
.salut__titre { font-size: 1.7rem; font-weight: 780; letter-spacing: -0.04em; }
.salut__sous { font-size: 0.9rem; color: var(--d-ink-soft); }
.bascule { display: grid; grid-template-columns: 1fr 1fr; gap: 4px; padding: 4px; border-radius: 16px; background: var(--d-surface-3); }
.bascule button { display: flex; align-items: center; justify-content: center; gap: 8px; height: 46px; border: 0; border-radius: 13px; font: inherit; font-size: 0.95rem;
  font-weight: 700; color: var(--d-ink-soft); background: transparent; cursor: pointer; }
.bascule button b { min-width: 24px; height: 24px; border-radius: 999px; display: inline-grid; place-items: center; font-size: 0.78rem; background: var(--d-surface); }
.bascule button.actif { color: var(--d-ink); background: var(--d-surface); box-shadow: var(--d-shadow-1); }
.carte { padding: 16px; margin-bottom: 12px; border-radius: 22px; background: var(--d-surface); border: 1px solid var(--d-line); box-shadow: var(--d-shadow-2); }
.carte--urgente { box-shadow: var(--d-shadow-2), inset 4px 0 0 var(--menage-sale); }
.carte__tete { display: flex; gap: 14px; align-items: center; }
.carte__numero { min-width: 64px; height: 64px; padding: 0 8px; border-radius: 20px; display: grid; place-items: center; font-size: 1.6rem; font-weight: 800;
  letter-spacing: -0.04em; color: #fff; background: var(--d-grad); font-variant-numeric: tabular-nums; }
.carte__numero--icone { color: var(--c); background: color-mix(in srgb, var(--c) 12%, var(--d-surface)); }
.carte__texte { flex: 1; min-width: 0; display: flex; flex-direction: column; align-items: flex-start; }
.carte__texte strong { font-size: 1.08rem; letter-spacing: -0.01em; }
.carte__texte small { font-size: 0.84rem; color: var(--d-ink-soft); }
.carte__urgent { display: inline-flex; align-items: center; gap: 3px; margin-top: 4px; font-size: 0.78rem; font-weight: 700; color: var(--menage-sale); }
.carte__actions { display: flex; gap: 8px; margin-top: 14px; }
.carte__actions .v-btn:not(.v-btn--icon) { flex: 1; }
.signaler { position: fixed; left: 50%; bottom: 18px; transform: translateX(-50%); z-index: 20; display: inline-flex; align-items: center; gap: 10px; height: 56px;
  padding: 0 24px; border: 0; border-radius: 999px; cursor: pointer; font: inherit; font-size: 1rem; font-weight: 750; color: #fff;
  background: linear-gradient(135deg, #fb7185, #e11d48); box-shadow: 0 16px 30px -12px rgba(225, 29, 72, 0.7); }
.panne { background: var(--d-canvas) !important; }
.panne__tete { display: flex; align-items: center; gap: 8px; padding: 10px 12px; font-size: 1rem; }
.panne__corps { padding: 8px 18px 32px; max-width: 640px; margin: 0 auto; width: 100%; }
.categories { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.categories button { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 14px 4px; border-radius: 16px; border: 1px solid var(--d-line); cursor: pointer;
  font: inherit; font-size: 0.76rem; font-weight: 650; color: var(--d-ink-2); background: var(--d-surface); }
.categories button.actif { color: var(--color-primary); border-color: var(--color-primary); background: color-mix(in srgb, var(--color-primary) 8%, var(--d-surface)); }
.photo { display: flex; align-items: center; justify-content: center; gap: 12px; width: 100%; height: 72px; border-radius: 18px; cursor: pointer; font: inherit; font-weight: 700;
  color: var(--color-primary); background: color-mix(in srgb, var(--color-primary) 6%, var(--d-surface)); border: 1.5px dashed color-mix(in srgb, var(--color-primary) 45%, transparent); }
</style>
