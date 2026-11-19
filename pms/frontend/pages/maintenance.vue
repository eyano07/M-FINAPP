<script setup lang="ts">
import { useDisplay } from 'vuetify'
import { useEtablissementStore } from '~/stores/etablissement'
import { useSessionStore } from '~/stores/session'
import { CATEGORIES_TICKET, COMPTES_MAINTENANCE, COMPTE_PAR_CATEGORIE, PRIORITES, STATUTS_NOTE, STATUTS_TICKET, dateCourte, dateHeure, usd } from '~/utils/libelles'

/**
 * Maintenance : tout le personnel signale une panne (photo à l'appui) ; le technicien la prend, commande ses pièces
 * (note de frais M-FINAPP, circuit DFIN → DA → caisse) et la résout. Une panne peut mettre l'unité hors service jusqu'à
 * une date : elle disparaît alors du calendrier, de la réservation en ligne et des canaux pendant les travaux.
 */
useHead({ title: 'Maintenance' })

interface Ticket { id: number, reference: string, etablissementId: number, etablissementNom: string, uniteId: number | null, uniteNumero: string | null,
  lieu: string | null, categorie: string, priorite: string, titre: string, description: string | null, statut: string, horsService: boolean,
  horsServiceAu: string | null, assigneEmail: string | null, assigneNom: string | null, signalePar: string, signaleLe: string,
  commenceLe: string | null, resoluLe: string | null, resoluPar: string | null, resolution: string | null, nbPhotos: number, nbAchats: number }
interface Achat { id: number, objet: string, fournisseur: string | null, montant: number, devise: string, compte: string, noteReference: string | null,
  noteStatut: string | null, creePar: string, creeLe: string }
interface Conflit { reservationId: number, reference: string, client: string, debut: string, fin: string, statut: string }
interface Detail { ticket: Ticket, photos: { id: number, legende: string | null }[], achats: Achat[], conflits: Conflit[] }
interface Unite { id: number, numero: string, typeNom: string, etablissementId: number, etablissementNom: string, actif: boolean }

const api = useApi()
const session = useSessionStore()
const etablissements = useEtablissementStore()
const { smAndDown } = useDisplay()
const tickets = ref<Ticket[]>([])
const unites = ref<Unite[]>([])
const vue = ref<'ouverts' | 'moi' | 'tous'>(session.a('TECHNICIEN') && !session.a('DIR_HEBERGEMENT') ? 'moi' : 'ouverts')
const erreur = ref('')
const charge = ref(false)
const technicien = computed(() => session.a('TECHNICIEN', 'DIR_HEBERGEMENT', 'ADMIN'))
const direction = computed(() => session.a('DIR_HEBERGEMENT', 'ADMIN'))
const acheteur = computed(() => session.a('TECHNICIEN', 'DIR_HEBERGEMENT'))

async function charger() {
  try {
    tickets.value = await api<Ticket[]>('/maintenance/tickets', { query: {
      ouverts: vue.value !== 'tous', moi: vue.value === 'moi', etablissementId: etablissements.filtre } })
    erreur.value = ''
  } catch (e) { erreur.value = messageErreur(e) } finally { charge.value = true }
}
onMounted(async () => {
  await charger()
  try { unites.value = (await api<Unite[]>('/unites', { query: { etablissementId: etablissements.filtre } })).filter(u => u.actif) } catch { unites.value = [] }
})
watch(vue, charger)

const compteurs = computed(() => {
  const ouverts = tickets.value.filter(t => ['OUVERT', 'EN_COURS', 'ATTENTE_PIECES'].includes(t.statut))
  return [
    { libelle: 'À traiter', valeur: ouverts.length, icone: 'mdi-wrench-clock', c: '#2563eb' },
    { libelle: 'Urgents', valeur: ouverts.filter(t => ['HAUTE', 'URGENTE'].includes(t.priorite)).length, icone: 'mdi-alert-octagon-outline', c: '#e11d48' },
    { libelle: 'Unités hors service', valeur: ouverts.filter(t => t.horsService).length, icone: 'mdi-door-closed-lock', c: '#64748b' },
    { libelle: 'Attente de pièces', valeur: ouverts.filter(t => t.statut === 'ATTENTE_PIECES').length, icone: 'mdi-package-variant', c: '#7c3aed' },
  ]
})
const age = (iso: string) => {
  const h = Math.round((Date.now() - new Date(iso).getTime()) / 3_600_000)
  return h < 1 ? 'à l’instant' : h < 24 ? `il y a ${h} h` : `il y a ${Math.round(h / 24)} j`
}

// ── Signalement ─────────────────────────────────────────────────────────────────────────────────────────────
const sig = reactive({ ouvert: false, uniteId: null as number | null, lieu: '', categorie: 'PLOMBERIE', priorite: 'NORMALE', titre: '',
  description: '', horsService: false, horsServiceAu: '', enCours: false, erreur: '' })
function signaler(uniteId: number | null = null) {
  Object.assign(sig, { ouvert: true, uniteId, lieu: '', categorie: 'PLOMBERIE', priorite: 'NORMALE', titre: '', description: '', horsService: false,
    horsServiceAu: '', enCours: false, erreur: '' })
}
const optionsUnites = computed(() => unites.value.map(u => ({ value: u.id, title: `${u.numero} — ${u.typeNom}`, subtitle: u.etablissementNom })))
async function envoyerSignalement() {
  sig.erreur = ''
  sig.enCours = true
  try {
    const t = await api<Ticket>('/maintenance/tickets', { method: 'POST', body: {
      uniteId: sig.uniteId, etablissementId: sig.uniteId ? null : (etablissements.filtre ?? etablissements.liste[0]?.id), lieu: sig.lieu || null,
      categorie: sig.categorie, priorite: sig.priorite, titre: sig.titre, description: sig.description || null,
      horsService: sig.horsService, horsServiceAu: sig.horsService && sig.horsServiceAu ? new Date(sig.horsServiceAu).toISOString() : null } })
    sig.ouvert = false
    await charger()
    await ouvrir(t.id)
  } catch (e) { sig.erreur = messageErreur(e, "Le signalement n'a pas été enregistré.") } finally { sig.enCours = false }
}

// ── Détail ──────────────────────────────────────────────────────────────────────────────────────────────────
const detail = ref<Detail | null>(null)
const panneau = ref(false)
const action = ref('')
async function ouvrir(id: number) {
  try {
    detail.value = await api<Detail>(`/maintenance/tickets/${id}`)
    panneau.value = true
    Object.assign(achat, { ouvert: false })
  } catch (e) { erreur.value = messageErreur(e) }
}
async function agir(nom: string, f: () => Promise<unknown>) {
  if (!detail.value) return
  action.value = nom
  try {
    await f()
    await ouvrir(detail.value.ticket.id)
    await charger()
  } catch (e) { erreur.value = messageErreur(e) } finally { action.value = '' }
}
const t = computed(() => detail.value?.ticket)
const ouvertT = computed(() => !!t.value && ['OUVERT', 'EN_COURS', 'ATTENTE_PIECES'].includes(t.value.statut))
const statut = (s: string, resolution: string | null = null) => agir(s, () => api(`/maintenance/tickets/${t.value!.id}/statut`, { method: 'POST', body: { statut: s, resolution } }))
const prendre = () => agir('prendre', () => api(`/maintenance/tickets/${t.value!.id}/affectation`, { method: 'PUT', body: {} }))
async function resoudre() {
  const texte = await useDialogues().saisir({ titre: 'Qu’avez-vous fait ?', message: 'Décrivez l’intervention : elle reste au dossier de l’unité.',
    libelle: 'Intervention réalisée', bouton: 'Marquer résolu', multiligne: true, obligatoire: true })
  if (texte) await statut('RESOLU', texte)
}
async function annuler() {
  if (await useDialogues().confirmer({ titre: `Annuler le ticket ${t.value!.reference} ?`, message: 'L’unité est rendue au service si ce ticket la bloquait.',
    bouton: 'Annuler le ticket', nature: 'attention' })) await statut('ANNULE')
}
async function affecter() {
  const nom = await useDialogues().saisir({ titre: 'Affecter à quel technicien ?', message: 'Son identifiant M-FINAPP (nom d’utilisateur ou e-mail).',
    libelle: 'Identifiant du technicien', bouton: 'Affecter', multiligne: false, obligatoire: true })
  if (nom) await agir('affecter', () => api(`/maintenance/tickets/${t.value!.id}/affectation`, { method: 'PUT', body: { email: nom, nom } }))
}
async function basculerHorsService(valeur: boolean) {
  const x = t.value!
  let au: string | null = null
  if (valeur) {
    const saisie = await useDialogues().saisir({ titre: `Mettre l’unité ${x.uniteNumero} hors service ?`, message: 'Fin prévue des travaux (laissez vide : jusqu’à la résolution).',
      libelle: 'Fin prévue (AAAA-MM-JJ)', bouton: 'Mettre hors service', multiligne: false, placeholder: '2026-12-31', nature: 'attention' })
    if (saisie === null) return
    if (saisie && !/^\d{4}-\d{2}-\d{2}$/.test(saisie.trim())) { erreur.value = 'Date attendue au format AAAA-MM-JJ.'; return }
    au = saisie ? new Date(`${saisie.trim()}T12:00:00`).toISOString() : null
  }
  await agir('hs', () => api(`/maintenance/tickets/${x.id}`, { method: 'PUT', body: { categorie: x.categorie, priorite: x.priorite, titre: x.titre,
    description: x.description, horsService: valeur, horsServiceAu: au } }))
}

// ── Achats ──────────────────────────────────────────────────────────────────────────────────────────────────
const achat = reactive({ ouvert: false, cle: '', objet: '', fournisseur: '', montant: null as number | null, compte: '6241', description: '', justification: '', enCours: false, erreur: '' })
function nouvelAchat() {
  Object.assign(achat, { ouvert: true, cle: nouvelleCle('ACHAT'), objet: '', fournisseur: '', montant: null,
    compte: COMPTE_PAR_CATEGORIE[t.value?.categorie ?? 'AUTRE'] ?? '6248', description: '', justification: '', enCours: false, erreur: '' })
}
async function demanderAchat() {
  achat.erreur = ''
  achat.enCours = true
  try {
    detail.value = await api<Detail>(`/maintenance/tickets/${t.value!.id}/achats`, { method: 'POST', body: {
      cle: achat.cle, objet: achat.objet, fournisseur: achat.fournisseur || null, montant: achat.montant, compte: achat.compte,
      description: achat.description || null, justificationBudget: achat.justification || null } })
    achat.ouvert = false
    await charger()
  } catch (e) { achat.erreur = messageErreur(e, "La demande d'achat n'est pas partie.") } finally { achat.enCours = false }
}
</script>

<template>
  <div class="d-apparait">
    <div class="page-head">
      <div>
        <div class="page-eyebrow">Opérations</div>
        <h1 class="page-title">Maintenance</h1>
        <p class="page-sub">Pannes signalées, interventions et achats de pièces</p>
      </div>
      <v-btn color="primary" variant="flat" size="large" prepend-icon="mdi-alert-plus-outline" @click="signaler()">Signaler une panne</v-btn>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-4" @click:close="erreur = ''">{{ erreur }}</v-alert>

    <div class="compteurs mb-5">
      <div v-for="c in compteurs" :key="c.libelle" class="d-carte compteur">
        <span class="d-icone d-icone--sm" :style="{ '--c': c.c }"><v-icon :icon="c.icone" size="18" /></span>
        <div><div class="compteur__valeur">{{ c.valeur }}</div><div class="compteur__libelle">{{ c.libelle }}</div></div>
      </div>
    </div>

    <div class="d-filtres mb-4">
      <button v-for="f in ([{ v: 'ouverts', t: 'À traiter' }, ...(technicien ? [{ v: 'moi', t: 'Mes tickets' }] : []), { v: 'tous', t: 'Historique' }] as const)" :key="f.v"
        type="button" class="d-filtre" :class="{ 'd-filtre--actif': vue === f.v }" style="--c: var(--color-primary)" @click="vue = f.v">
        <span class="d-filtre__point" />{{ f.t }}
      </button>
    </div>

    <div v-if="tickets.length" class="tickets">
      <button v-for="x in tickets" :key="x.id" type="button" class="ticket" :class="`ticket--${x.statut.toLowerCase()}`" @click="ouvrir(x.id)">
        <div class="ticket__tete">
          <span class="d-icone" :style="{ '--c': PRIORITES[x.priorite]?.couleur }"><v-icon :icon="CATEGORIES_TICKET[x.categorie]?.icone" size="20" /></span>
          <div class="ticket__titres">
            <div class="ticket__titre">{{ x.titre }}</div>
            <div class="ticket__ref">{{ x.reference }} · {{ CATEGORIES_TICKET[x.categorie]?.libelle }}</div>
          </div>
        </div>
        <div class="ticket__lieu">
          <span class="ticket__unite">{{ x.uniteNumero ?? '—' }}</span>
          <span>{{ x.uniteNumero ? x.etablissementNom : x.lieu }}</span>
          <span v-if="x.horsService" class="ticket__hs"><v-icon icon="mdi-door-closed-lock" size="13" />hors service</span>
        </div>
        <div class="ticket__pied">
          <span class="pastille" :style="{ '--c': STATUTS_TICKET[x.statut]?.couleur }">{{ STATUTS_TICKET[x.statut]?.libelle }}</span>
          <span v-if="['HAUTE', 'URGENTE'].includes(x.priorite)" class="pastille" :style="{ '--c': PRIORITES[x.priorite]?.couleur }">{{ PRIORITES[x.priorite]?.libelle }}</span>
          <v-spacer />
          <span v-if="x.nbPhotos" class="ticket__meta"><v-icon icon="mdi-image-outline" size="14" />{{ x.nbPhotos }}</span>
          <span v-if="x.nbAchats" class="ticket__meta"><v-icon icon="mdi-cart-outline" size="14" />{{ x.nbAchats }}</span>
          <span class="ticket__meta">{{ age(x.signaleLe) }}</span>
        </div>
        <div v-if="x.assigneNom" class="ticket__assigne"><v-icon icon="mdi-account-wrench-outline" size="14" />{{ x.assigneNom }}</div>
      </button>
    </div>
    <div v-else-if="charge" class="d-carte">
      <div class="d-vide">
        <div class="d-vide__icone"><v-icon icon="mdi-check-decagram-outline" size="28" /></div>
        <div class="d-vide__titre">{{ vue === 'tous' ? 'Aucun ticket pour l’instant' : 'Rien à réparer' }}</div>
        <div class="d-vide__texte">Une fuite, une clim en panne, une serrure bloquée ? « Signaler une panne », photo à l’appui : le technicien est prévenu.</div>
      </div>
    </div>

    <!-- Signalement -->
    <v-dialog v-model="sig.ouvert" max-width="620" :fullscreen="smAndDown" scrollable>
      <v-card class="pa-2">
        <v-card-title class="text-h6 font-weight-bold">Signaler une panne</v-card-title>
        <v-card-text>
          <v-alert v-if="sig.erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ sig.erreur }}</v-alert>
          <v-autocomplete v-model="sig.uniteId" :items="optionsUnites" label="Unité concernée" clearable prepend-inner-icon="mdi-bed-outline">
            <template #item="{ props: p, item }"><v-list-item v-bind="p" :subtitle="item.raw.subtitle" /></template>
          </v-autocomplete>
          <v-text-field v-if="!sig.uniteId" v-model="sig.lieu" label="Ou le lieu (hall, piscine, cuisine…)" prepend-inner-icon="mdi-map-marker-outline" />
          <div class="text-caption text-medium-emphasis mb-2">Catégorie</div>
          <div class="categories mb-4">
            <button v-for="(c, cle) in CATEGORIES_TICKET" :key="cle" type="button" class="categorie" :class="{ 'categorie--active': sig.categorie === cle }" @click="sig.categorie = cle as string">
              <v-icon :icon="c.icone" size="20" /><span>{{ c.libelle }}</span>
            </button>
          </div>
          <v-text-field v-model="sig.titre" label="En quelques mots *" placeholder="Fuite sous le lavabo, climatiseur qui ne refroidit plus…" />
          <v-textarea v-model="sig.description" label="Détails" rows="2" auto-grow />
          <div class="text-caption text-medium-emphasis mb-2">Priorité</div>
          <v-btn-toggle v-model="sig.priorite" mandatory divided variant="outlined" density="comfortable" class="mb-4 priorites">
            <v-btn v-for="(p, cle) in PRIORITES" :key="cle" :value="cle">{{ p.libelle }}</v-btn>
          </v-btn-toggle>
          <template v-if="sig.uniteId">
            <v-switch v-model="sig.horsService" color="error" inset hide-details label="L’unité est inutilisable : la mettre hors service" />
            <v-text-field v-if="sig.horsService" v-model="sig.horsServiceAu" type="datetime-local" label="Fin prévue des travaux (facultatif)" class="mt-3"
              hint="Les réservations qui commencent après restent possibles." persistent-hint />
          </template>
        </v-card-text>
        <v-card-actions class="px-6 pb-4"><v-spacer /><v-btn variant="text" @click="sig.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="sig.enCours" :disabled="!sig.titre.trim() || (!sig.uniteId && !sig.lieu.trim())" @click="envoyerSignalement">Signaler</v-btn></v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Détail d'un ticket -->
    <v-navigation-drawer v-model="panneau" location="right" temporary :width="smAndDown ? 380 : 560" class="volet">
      <div v-if="t" class="volet__contenu">
        <div class="volet__tete">
          <span class="d-icone d-icone--lg" :style="{ '--c': PRIORITES[t.priorite]?.couleur }"><v-icon :icon="CATEGORIES_TICKET[t.categorie]?.icone" size="24" /></span>
          <div class="volet__titres">
            <div class="volet__ref">{{ t.reference }} · {{ CATEGORIES_TICKET[t.categorie]?.libelle }}</div>
            <h2 class="volet__titre">{{ t.titre }}</h2>
            <div class="d-flex flex-wrap ga-2 mt-2">
              <span class="pastille" :style="{ '--c': STATUTS_TICKET[t.statut]?.couleur }">{{ STATUTS_TICKET[t.statut]?.libelle }}</span>
              <span class="pastille" :style="{ '--c': PRIORITES[t.priorite]?.couleur }">Priorité {{ PRIORITES[t.priorite]?.libelle.toLowerCase() }}</span>
            </div>
          </div>
          <v-btn icon="mdi-close" variant="text" size="small" aria-label="Fermer" @click="panneau = false" />
        </div>

        <div v-if="technicien && ouvertT" class="volet__actions">
          <v-btn v-if="t.statut === 'OUVERT'" color="primary" variant="flat" prepend-icon="mdi-play" :loading="action === 'EN_COURS'" @click="statut('EN_COURS')">Commencer</v-btn>
          <v-btn v-if="t.statut === 'ATTENTE_PIECES'" variant="tonal" prepend-icon="mdi-play" :loading="action === 'EN_COURS'" @click="statut('EN_COURS')">Reprendre</v-btn>
          <v-btn v-if="t.statut !== 'OUVERT'" color="success" variant="flat" prepend-icon="mdi-check" :loading="action === 'RESOLU'" @click="resoudre">Résolu</v-btn>
          <v-btn v-if="!t.assigneEmail || t.assigneEmail !== session.utilisateur?.email" variant="tonal" prepend-icon="mdi-hand-back-right-outline" :loading="action === 'prendre'" @click="prendre">Je m’en charge</v-btn>
          <v-menu location="bottom end">
            <template #activator="{ props }"><v-btn icon="mdi-dots-horizontal" variant="text" v-bind="props" aria-label="Autres actions" /></template>
            <v-list density="compact">
              <v-list-item v-if="t.statut === 'EN_COURS'" prepend-icon="mdi-package-variant" title="En attente de pièces" @click="statut('ATTENTE_PIECES')" />
              <v-list-item v-if="direction" prepend-icon="mdi-account-arrow-right-outline" title="Affecter à…" @click="affecter" />
              <v-list-item prepend-icon="mdi-close-circle-outline" title="Annuler le ticket" @click="annuler" />
            </v-list>
          </v-menu>
        </div>

        <section class="volet__bloc">
          <div class="volet__libelle">Où</div>
          <div class="volet__ligne"><v-icon icon="mdi-map-marker-outline" size="18" />{{ t.uniteNumero ? `Unité ${t.uniteNumero} · ${t.etablissementNom}` : `${t.lieu} · ${t.etablissementNom}` }}</div>
          <div class="volet__ligne"><v-icon icon="mdi-account-outline" size="18" />Signalé par {{ t.signalePar }}, {{ dateHeure(t.signaleLe) }}</div>
          <div v-if="t.assigneNom" class="volet__ligne"><v-icon icon="mdi-account-wrench-outline" size="18" />Technicien : {{ t.assigneNom }}</div>
          <p v-if="t.description" class="volet__texte">{{ t.description }}</p>
          <div v-if="t.resolution" class="volet__resolution"><v-icon icon="mdi-check-circle" color="success" size="18" />
            <div><strong>{{ t.statut === 'ANNULE' ? 'Annulé' : 'Résolu' }} par {{ t.resoluPar }}, {{ dateHeure(t.resoluLe) }}</strong><div>{{ t.resolution }}</div></div></div>
        </section>

        <section v-if="t.uniteId" class="volet__bloc">
          <div class="d-flex align-center">
            <div class="volet__libelle mb-0">Disponibilité de l’unité</div><v-spacer />
            <v-switch v-if="technicien && ouvertT" :model-value="t.horsService" color="error" inset hide-details density="compact" :disabled="!!action"
              @update:model-value="(v: boolean | null) => basculerHorsService(!!v)" />
          </div>
          <div v-if="t.horsService" class="hs">
            <v-icon icon="mdi-door-closed-lock" size="20" />
            <div><strong>Hors service{{ t.horsServiceAu ? ` jusqu’au ${dateCourte(t.horsServiceAu)}` : ' jusqu’à la résolution' }}</strong>
              <div class="text-caption">Bloquée dans le calendrier, la réservation en ligne et les canaux.</div></div>
          </div>
          <div v-else class="text-body-2 text-medium-emphasis">L’unité reste réservable pendant l’intervention.</div>
          <div v-if="detail?.conflits.length" class="conflits">
            <div class="conflits__titre"><v-icon icon="mdi-alert-outline" size="16" />{{ detail.conflits.length }} séjour(s) à reloger</div>
            <NuxtLink v-for="c in detail.conflits" :key="c.reservationId" :to="`/reservations/${c.reservationId}`" class="conflits__ligne">
              <strong>{{ c.client }}</strong><span>{{ c.reference }} · {{ dateCourte(c.debut) }} → {{ dateCourte(c.fin) }}</span>
            </NuxtLink>
          </div>
        </section>

        <section class="volet__bloc">
          <div class="volet__libelle">Photos</div>
          <GaleriePhotos :photos="detail?.photos ?? []" :envoi="`/maintenance/tickets/${t.id}/photos`" :peut-ajouter="ouvertT" @change="ouvrir(t.id)" />
        </section>

        <section class="volet__bloc">
          <div class="d-flex align-center mb-2">
            <div class="volet__libelle mb-0">Achats de pièces</div><v-spacer />
            <v-btn v-if="acheteur && ouvertT && !achat.ouvert" size="small" variant="tonal" prepend-icon="mdi-cart-plus" @click="nouvelAchat">Demander un achat</v-btn>
          </div>
          <div v-for="a in detail?.achats ?? []" :key="a.id" class="achat">
            <span class="d-icone d-icone--sm" style="--c: #7c3aed"><v-icon icon="mdi-receipt-text-outline" size="16" /></span>
            <div class="achat__texte"><strong>{{ a.objet }}</strong><small>{{ a.noteReference }} · compte {{ a.compte }}{{ a.fournisseur ? ` · ${a.fournisseur}` : '' }}</small></div>
            <div class="text-right"><div class="achat__montant">{{ usd(a.montant) }}</div>
              <span class="pastille" :style="{ '--c': STATUTS_NOTE[a.noteStatut ?? '']?.couleur ?? '#64748b' }">{{ STATUTS_NOTE[a.noteStatut ?? '']?.libelle ?? a.noteStatut }}</span></div>
          </div>
          <p v-if="!detail?.achats.length && !achat.ouvert" class="text-body-2 text-medium-emphasis mb-0">Aucun achat. Une demande devient une note de frais dans M-FINAPP (DFIN, DA, caisse) ; son avancement s’affiche ici.</p>
          <div v-if="achat.ouvert" class="achat-form">
            <v-alert v-if="achat.erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ achat.erreur }}</v-alert>
            <v-text-field v-model="achat.objet" label="Quoi ? *" placeholder="Compresseur de climatiseur, joint, prestation du frigoriste…" />
            <v-row dense>
              <v-col cols="12" sm="6"><v-text-field v-model.number="achat.montant" type="number" min="0" label="Montant (USD) *" prefix="$" /></v-col>
              <v-col cols="12" sm="6"><v-text-field v-model="achat.fournisseur" label="Fournisseur" /></v-col>
            </v-row>
            <v-select v-model="achat.compte" :items="COMPTES_MAINTENANCE" label="Compte d’imputation" />
            <v-textarea v-model="achat.justification" label="Justification (si hors budget)" rows="1" auto-grow />
            <div class="d-flex ga-2 justify-end">
              <v-btn variant="text" @click="achat.ouvert = false">Annuler</v-btn>
              <v-btn color="primary" variant="flat" prepend-icon="mdi-send" :loading="achat.enCours" :disabled="!achat.objet.trim() || !achat.montant" @click="demanderAchat">Envoyer au DFIN</v-btn>
            </div>
          </div>
        </section>
      </div>
    </v-navigation-drawer>
  </div>
</template>

<style scoped>
.compteurs { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.compteur { display: flex; align-items: center; gap: 14px; padding: 16px 18px; }
.compteur__valeur { font-size: 1.6rem; font-weight: 780; letter-spacing: -0.04em; line-height: 1; font-variant-numeric: tabular-nums; }
.compteur__libelle { font-size: 0.8rem; color: var(--d-ink-soft); margin-top: 4px; }

.tickets { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; }
.ticket {
  display: flex; flex-direction: column; gap: 12px; padding: 18px; text-align: left; cursor: pointer; font: inherit; color: inherit;
  border-radius: var(--d-r-lg); background: var(--d-surface); border: 1px solid var(--d-line); box-shadow: var(--d-shadow-1);
  transition: transform 0.2s, box-shadow 0.2s;
}
.ticket:hover { transform: translateY(-2px); box-shadow: var(--d-shadow-3); }
.ticket--resolu, .ticket--annule { opacity: 0.7; }
.ticket__tete { display: flex; gap: 12px; align-items: flex-start; }
.ticket__titres { min-width: 0; }
.ticket__titre { font-weight: 720; font-size: 0.98rem; letter-spacing: -0.015em; line-height: 1.3; }
.ticket__ref { font-size: 0.76rem; color: var(--d-ink-faint); margin-top: 2px; }
.ticket__lieu { display: flex; align-items: center; gap: 10px; font-size: 0.84rem; color: var(--d-ink-2); }
.ticket__unite { min-width: 42px; padding: 3px 9px; border-radius: 9px; text-align: center; font-weight: 780; font-variant-numeric: tabular-nums; background: var(--d-surface-3); }
.ticket__hs { display: inline-flex; align-items: center; gap: 3px; font-size: 0.72rem; font-weight: 700; color: var(--menage-sale); }
.ticket__pied { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ticket__meta { display: inline-flex; align-items: center; gap: 3px; font-size: 0.75rem; color: var(--d-ink-faint); }
.ticket__assigne { display: inline-flex; align-items: center; gap: 5px; font-size: 0.78rem; color: var(--d-ink-soft); padding-top: 10px; border-top: 1px dashed var(--d-line-strong); }

.categories { display: grid; grid-template-columns: repeat(auto-fill, minmax(104px, 1fr)); gap: 8px; }
.categorie {
  display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 10px 6px; border-radius: 14px; cursor: pointer; font: inherit;
  font-size: 0.74rem; font-weight: 600; color: var(--d-ink-2); background: var(--d-surface-2); border: 1px solid var(--d-line); transition: all 0.15s;
}
.categorie--active { color: var(--color-primary); border-color: var(--color-primary); background: color-mix(in srgb, var(--color-primary) 8%, var(--d-surface)); }
.priorites { width: 100%; }
.priorites .v-btn { flex: 1; }

.volet { border-radius: 24px 0 0 24px !important; }
.volet__contenu { padding: 20px 22px 40px; display: flex; flex-direction: column; gap: 18px; }
.volet__tete { display: flex; gap: 14px; align-items: flex-start; }
.volet__titres { flex: 1; min-width: 0; }
.volet__ref { font-size: 0.76rem; color: var(--d-ink-faint); }
.volet__titre { font-size: 1.25rem; font-weight: 760; letter-spacing: -0.025em; line-height: 1.25; margin: 2px 0 0; }
.volet__actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.volet__bloc { padding: 16px; border-radius: 18px; background: var(--d-surface-2); border: 1px solid var(--d-line); }
.volet__libelle { font-size: 0.7rem; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: var(--d-ink-faint); margin-bottom: 10px; }
.volet__ligne { display: flex; align-items: center; gap: 8px; font-size: 0.88rem; color: var(--d-ink-2); padding: 3px 0; }
.volet__ligne .v-icon { color: var(--d-ink-faint); }
.volet__texte { margin: 10px 0 0; font-size: 0.9rem; line-height: 1.55; white-space: pre-line; }
.volet__resolution { display: flex; gap: 10px; margin-top: 12px; padding: 12px; border-radius: 14px; font-size: 0.86rem; background: color-mix(in srgb, #16a34a 7%, var(--d-surface)); }
.hs { display: flex; gap: 10px; align-items: flex-start; padding: 12px; border-radius: 14px; color: var(--menage-sale);
  background: color-mix(in srgb, var(--menage-sale) 7%, var(--d-surface)); }
.hs .text-caption { color: var(--d-ink-soft); }
.conflits { margin-top: 12px; }
.conflits__titre { display: flex; align-items: center; gap: 6px; font-size: 0.8rem; font-weight: 700; color: #d97706; margin-bottom: 6px; }
.conflits__ligne { display: flex; flex-direction: column; padding: 8px 10px; border-radius: 12px; text-decoration: none; color: inherit; background: var(--d-surface); margin-top: 4px; }
.conflits__ligne span { font-size: 0.78rem; color: var(--d-ink-soft); }
.achat { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px dashed var(--d-line-strong); }
.achat:last-of-type { border-bottom: 0; }
.achat__texte { flex: 1; min-width: 0; }
.achat__texte strong { display: block; font-size: 0.88rem; }
.achat__texte small { display: block; font-size: 0.76rem; color: var(--d-ink-faint); }
.achat__montant { font-weight: 750; font-variant-numeric: tabular-nums; margin-bottom: 4px; }
.achat-form { margin-top: 10px; padding: 14px; border-radius: 14px; background: var(--d-surface); border: 1px solid var(--d-line); }

@media (max-width: 959px) { .compteurs { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
