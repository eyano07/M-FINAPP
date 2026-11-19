<script setup lang="ts">
import { useSessionStore } from '~/stores/session'
import { CANAUX, DUREES, MODES, STATUTS, dateCourte, dateHeure, jourIso, usd } from '~/utils/libelles'

/**
 * Séjour : arrivée (unité, code d'accès), folio (hébergement + extras), acomptes et cautions encaissés dans M-FINAPP,
 * départ (le folio devient une vente M-FINAPP, encaissée par la réception, acompte et caution imputés).
 */
useHead({ title: 'Séjour' })

interface Ligne { id: number | null, nature: string, articleId: number | null, designation: string, quantite: number, prixUnitaire: number,
  montantHt: number, tva: number, stocke: boolean, date: string }
interface Fonds { id: number, type: string, montant: number, mode: string, pieceReference: string | null, creeLe: string, creePar: string }
interface Folio {
  reservation: any, lignes: Ligne[], fonds: Fonds[], totalHt: number, totalTva: number, totalTtc: number, acomptes: number,
  cautions: number, resteAPayer: number, tauxTva: number, devise: string
}
interface Referentiel { banques: { id: number, nom: string }[], mobileMoney: { id: number, nom: string }[], entrepots: { id: number, nom: string, code: string }[],
  articles: { id: number, code: string, libelle: string, type: string, prixVente: number | null }[] }

const api = useApi()
const session = useSessionStore()
const dialogues = useDialogues()
const route = useRoute()
const id = Number(route.params.id)
const folio = ref<Folio | null>(null)
const ref_ = ref<Referentiel | null>(null)
const erreur = ref('')
const succes = ref('')
const action = ref('')
const r = computed(() => folio.value?.reservation)
const encaisseur = computed(() => session.a('RECEPTIONNISTE', 'DIR_HEBERGEMENT'))
const exploitant = computed(() => session.a('RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN'))

async function charger() {
  try {
    folio.value = await api<Folio>(`/reservations/${id}`)
    erreur.value = ''
  } catch (e) {
    erreur.value = messageErreur(e, 'Séjour introuvable.')
  }
}
async function chargerReferentiel() {
  if (ref_.value) return
  try { ref_.value = await api<Referentiel>('/referentiel') } catch (e) { erreur.value = messageErreur(e, 'Référentiel de M-FINAPP indisponible.') }
}
onMounted(() => { charger().then(chargerAnnexes) })

// ── Phase 3 : véhicules, bail, états des lieux ──────────────────────────────────────────────────────────────
interface Vehicule { id: number, immatriculation: string, marque: string | null, couleur: string | null, placeNumero: string | null, entreeLe: string, sortieLe: string | null }
interface PlaceLibre { id: number, numero: string, actif: boolean, vehiculeId: number | null, etablissementId: number }
const vehicules = ref<Vehicule[]>([])
const placesLibres = ref<PlaceLibre[]>([])
const bail = ref<{ id: number, reference: string } | null>(null)
const retenueProposee = ref(0)
const veh = reactive({ ouvert: false, immatriculation: '', marque: '', couleur: '', placeId: null as number | null })
async function chargerAnnexes() {
  if (!r.value) return
  try { vehicules.value = await api<Vehicule[]>('/parking/vehicules', { query: { reservationId: id } }) } catch { vehicules.value = [] }
  try {
    placesLibres.value = (await api<PlaceLibre[]>('/parking/places', { query: { etablissementId: r.value.etablissementId } })).filter(p => p.actif && !p.vehiculeId)
  } catch { placesLibres.value = [] }
  if (['MOIS', 'SEMAINE'].includes(r.value.uniteDuree)) {
    try { bail.value = (await api<{ id: number, reference: string, reservationId: number }[]>('/baux')).find(b => b.reservationId === Number(id)) ?? null } catch { bail.value = null }
  }
}
async function ajouterVehicule() {
  try {
    await api(`/reservations/${id}/vehicules`, { method: 'POST', body: { immatriculation: veh.immatriculation, marque: veh.marque || null, couleur: veh.couleur || null, placeId: veh.placeId } })
    Object.assign(veh, { ouvert: false, immatriculation: '', marque: '', couleur: '', placeId: null })
    await chargerAnnexes()
  } catch (e) { erreur.value = messageErreur(e) }
}
async function sortirVehicule(v: Vehicule) {
  try { await api(`/parking/vehicules/${v.id}/sortie`, { method: 'POST' }); await chargerAnnexes() } catch (e) { erreur.value = messageErreur(e) }
}
const logement = computed(() => !!r.value && (['MOIS', 'SEMAINE'].includes(r.value.uniteDuree) || !!bail.value))

async function executer(nom: string, f: () => Promise<unknown>, ok?: string) {
  action.value = nom
  erreur.value = ''
  succes.value = ''
  try {
    const res: any = await f()
    if (res && res.reservation && res.lignes) folio.value = res
    else await charger()
    if (ok) succes.value = ok
    return res
  } catch (e) {
    erreur.value = messageErreur(e)
    return null
  } finally {
    action.value = ''
  }
}

// ── Arrivée ─────────────────────────────────────────────────────────────
const arrivee = reactive({ ouvert: false, code: '', forcer: false })
async function arriver() {
  const ok = await executer('arrivee', () => api(`/reservations/${id}/arrivee`, { method: 'POST', body: { codeAcces: arrivee.code || null, forcer: arrivee.forcer } }),
    'Client arrivé : bon séjour !')
  if (ok) arrivee.ouvert = false
}

async function changerStatut(statut: string, titre: string) {
  let motif: string | null = null
  if (statut !== 'CONFIRMEE') {
    motif = await dialogues.saisir({ titre, libelle: 'Motif', nature: 'attention', bouton: 'Confirmer' })
    if (motif === null) return
  }
  await executer('statut', () => api(`/reservations/${id}/statut`, { method: 'POST', body: { statut, motif } }))
}

// ── Modification (dates, prix) ─────────────────────────────────────────
const modif = reactive({ ouvert: false, dateArrivee: '', dateDepart: '', prix: null as number | null, notes: '', clientId: null as number | null })
const clientsTrouves = ref<{ value: number, title: string }[]>([])
const rechercheClient = ref('')
watch(rechercheClient, async (q) => {
  try {
    const l = await api<{ id: number, nom: string, prenom: string | null, telephone: string | null }[]>('/clients', { query: { q: q || undefined } })
    clientsTrouves.value = l.map(c => ({ value: c.id, title: `${c.prenom ? c.prenom + ' ' : ''}${c.nom}${c.telephone ? ' · ' + c.telephone : ''}` }))
  } catch { clientsTrouves.value = [] }
})
function ouvrirModif() {
  Object.assign(modif, {
    ouvert: true, dateArrivee: jourIso(new Date(r.value.debut)), dateDepart: jourIso(new Date(r.value.fin)),
    prix: r.value.prixUnitaire, notes: r.value.notes ?? '', clientId: null,
  })
  rechercheClient.value = ''
}
async function enregistrerModif() {
  const corps: Record<string, unknown> = { prixUnitaire: modif.prix, notes: modif.notes, clientId: modif.clientId }
  if (r.value.uniteDuree !== 'HEURE') {
    corps.dateArrivee = modif.dateArrivee
    corps.dateDepart = modif.dateDepart
  }
  const ok = await executer('modif', () => api(`/reservations/${id}`, { method: 'PUT', body: corps }), 'Séjour modifié.')
  if (ok) modif.ouvert = false
}

// ── Extras ──────────────────────────────────────────────────────────────
const extra = reactive({ ouvert: false, articleId: null as number | null, quantite: 1, prix: null as number | null, designation: '' })
async function ouvrirExtra() {
  await chargerReferentiel()
  Object.assign(extra, { ouvert: true, articleId: null, quantite: 1, prix: null, designation: '' })
}
watch(() => extra.articleId, (a) => {
  const art = ref_.value?.articles.find(x => x.id === a)
  if (art) {
    extra.designation = art.libelle
    if (art.prixVente != null) extra.prix = Number(art.prixVente)
  }
})
async function ajouterExtra() {
  const art = ref_.value?.articles.find(x => x.id === extra.articleId)
  if (!art) { erreur.value = 'Choisissez un article de M-FINAPP.'; return }
  const ok = await executer('extra', () => api(`/reservations/${id}/extras`, {
    method: 'POST',
    body: { articleId: art.id, articleCode: art.code, designation: extra.designation || art.libelle, quantite: extra.quantite,
      prixUnitaire: extra.prix ?? 0, stocke: ['MARCHANDISE', 'BOISSON', 'PLAT'].includes(art.type) },
  }))
  if (ok) extra.ouvert = false
}
async function retirerExtra(l: Ligne) {
  if (!(await dialogues.confirmer({ titre: 'Retirer cet extra ?', message: l.designation, bouton: 'Retirer', nature: 'danger' }))) return
  await executer('retirer', () => api(`/reservations/${id}/extras/${l.id}`, { method: 'DELETE' }))
}

// ── Acomptes, cautions ─────────────────────────────────────────────────
const fonds = reactive({ ouvert: false, type: 'ACOMPTE', montant: null as number | null, mode: 'CAISSE', etablissementId: null as number | null, cle: '' })
const TITRES_FONDS: Record<string, string> = {
  ACOMPTE: 'Encaisser un acompte', CAUTION: 'Encaisser une caution', RESTITUTION: 'Rendre la caution', REMB_ACOMPTE: "Rembourser l'acompte",
}
async function ouvrirFonds(type: string) {
  await chargerReferentiel()
  const montant = type === 'RESTITUTION' ? folio.value?.cautions : type === 'REMB_ACOMPTE' ? folio.value?.acomptes : null
  Object.assign(fonds, { ouvert: true, type, montant: montant ? Number(montant) : null, mode: 'CAISSE', etablissementId: null, cle: nouvelleCle(`PMS-${type}`) })
}
const etablissementsMode = (mode: string) => mode === 'BANQUE' ? ref_.value?.banques ?? [] : mode === 'MOBILE_MONEY' ? ref_.value?.mobileMoney ?? [] : []
async function enregistrerFonds() {
  const ok = await executer('fonds', () => api(`/reservations/${id}/fonds`, {
    method: 'POST',
    body: { type: fonds.type, montant: fonds.montant, mode: fonds.mode, etablissementId: fonds.etablissementId, cle: fonds.cle },
  }), 'Enregistré dans M-FINAPP.')
  if (ok) fonds.ouvert = false
}

// ── Départ ──────────────────────────────────────────────────────────────
const depart = reactive({ ouvert: false, mode: 'CAISSE', etablissementId: null as number | null, entrepotId: null as number | null,
  acompteImpute: 0, cautionRetenue: 0, restituerCaution: true, cle: '' })
const avecStock = computed(() => folio.value?.lignes.some(l => l.stocke))
async function ouvrirDepart() {
  await chargerReferentiel()
  const f = folio.value!
  Object.assign(depart, {
    ouvert: true, mode: 'CAISSE', etablissementId: null, entrepotId: ref_.value?.entrepots.length === 1 ? ref_.value.entrepots[0].id : null,
    acompteImpute: Math.min(Number(f.acomptes), Number(f.totalTtc)),
    // Retenue proposée par l'état des lieux de sortie (dans la limite de la caution détenue).
    cautionRetenue: Math.min(retenueProposee.value, Number(f.cautions)), restituerCaution: true,
    cle: nouvelleCle(`PMS-FOLIO-${r.value.reference}`),
  })
}
const aEncaisser = computed(() => {
  const f = folio.value
  if (!f) return 0
  return Math.max(0, Number(f.totalTtc) - Number(depart.acompteImpute || 0) - Number(depart.cautionRetenue || 0))
})
async function partir() {
  const res: any = await executer('depart', () => api(`/reservations/${id}/depart`, {
    method: 'POST',
    body: {
      mode: depart.mode, etablissementId: depart.etablissementId, entrepotId: depart.entrepotId,
      acompteImpute: depart.mode === 'CREDIT' ? 0 : depart.acompteImpute, cautionRetenue: depart.mode === 'CREDIT' ? 0 : depart.cautionRetenue,
      restituerCaution: depart.restituerCaution, cle: depart.cle,
    },
  }))
  if (res) {
    depart.ouvert = false
    if (res.encaisse) succes.value = res.message
    else erreur.value = res.message
  }
}
async function reessayer() {
  const res: any = await executer('encaisser', () => api(`/reservations/${id}/encaisser`, { method: 'POST' }))
  if (res) {
    if (res.encaisse) succes.value = res.message
    else erreur.value = res.message
  }
}

const libelleFonds: Record<string, string> = { ACOMPTE: 'Acompte', CAUTION: 'Caution', RESTITUTION: 'Caution rendue', REMB_ACOMPTE: 'Acompte remboursé' }
const libDuree = (d: string) => DUREES.find(x => x.value === d)?.title ?? d
const libCanal = (c: string) => CANAUX.find(x => x.value === c)?.title ?? c
</script>

<template>
  <div v-if="r">
    <div class="page-head">
      <div>
        <div class="d-flex align-center ga-3 flex-wrap">
          <h1 class="page-title">{{ r.clientNom }}</h1>
          <span class="pastille" :style="{ '--c': STATUTS[r.statut]?.couleur }">{{ STATUTS[r.statut]?.libelle }}</span>
          <v-chip v-if="r.encaissement === 'EN_ATTENTE'" color="warning" size="small" variant="tonal">Encaissement en attente</v-chip>
          <v-chip v-if="r.encaissement === 'ENCAISSE'" color="success" size="small" variant="tonal">Encaissé · {{ r.venteReference }}</v-chip>
          <v-chip v-if="r.encaissement === 'CREDIT'" color="info" size="small" variant="tonal">Facturé · {{ r.venteReference }}</v-chip>
        </div>
        <p class="page-sub">{{ r.reference }} · {{ r.etablissementNom }} · unité {{ r.uniteNumero }} ({{ r.typeNom }})</p>
      </div>
      <div class="d-flex flex-wrap ga-2">
        <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/reservations">Réservations</v-btn>
        <template v-if="exploitant && ['OPTION', 'CONFIRMEE'].includes(r.statut)">
          <v-btn v-if="r.statut === 'OPTION'" variant="tonal" :loading="action === 'statut'" @click="changerStatut('CONFIRMEE', '')">Confirmer</v-btn>
          <v-btn color="primary" prepend-icon="mdi-login-variant" @click="Object.assign(arrivee, { ouvert: true, code: '', forcer: false })">Arrivée</v-btn>
        </template>
        <v-btn v-if="encaisseur && r.statut === 'ARRIVEE'" color="primary" prepend-icon="mdi-logout-variant" @click="ouvrirDepart">Départ et encaissement</v-btn>
        <v-btn v-if="encaisseur && r.encaissement === 'EN_ATTENTE'" color="warning" prepend-icon="mdi-cash-sync" :loading="action === 'encaisser'" @click="reessayer">
          Encaisser
        </v-btn>
        <v-menu v-if="exploitant && ['OPTION', 'CONFIRMEE', 'ARRIVEE'].includes(r.statut)">
          <template #activator="{ props }"><v-btn v-bind="props" icon="mdi-dots-vertical" variant="text" aria-label="Autres actions" /></template>
          <v-list density="compact">
            <v-list-item prepend-icon="mdi-calendar-edit" title="Modifier dates, prix, notes" @click="ouvrirModif" />
            <v-list-item v-if="r.statut === 'CONFIRMEE'" prepend-icon="mdi-account-off-outline" title="Non présenté" @click="changerStatut('NO_SHOW', 'Client non présenté ?')" />
            <v-list-item v-if="r.statut !== 'ARRIVEE'" prepend-icon="mdi-close-circle-outline" title="Annuler la réservation" @click="changerStatut('ANNULEE', 'Annuler la réservation ?')" />
          </v-list>
        </v-menu>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-4" @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" closable class="mb-4" @click:close="succes = ''">{{ succes }}</v-alert>

    <v-row>
      <v-col cols="12" md="4">
        <v-card class="classroom-card pa-5 mb-4">
          <div class="kpi-label mb-2">Séjour</div>
          <div class="info"><span>Arrivée</span><strong>{{ dateHeure(r.debut) }}</strong></div>
          <div class="info"><span>Départ</span><strong>{{ dateHeure(r.fin) }}</strong></div>
          <div class="info"><span>Durée</span><strong>{{ libDuree(r.uniteDuree) }}</strong></div>
          <div class="info"><span>Prix</span><strong>{{ usd(r.prixUnitaire) }} HT</strong></div>
          <div class="info"><span>Occupants</span><strong>{{ r.adultes }} adulte(s), {{ r.enfants }} enfant(s)</strong></div>
          <div class="info"><span>Provenance</span><strong>{{ libCanal(r.canal) }}<template v-if="r.canal === 'DIRECT'"> (en ligne)</template></strong></div>
          <div v-if="r.codeAcces" class="info"><span>Code d'accès</span><strong>{{ r.codeAcces }}</strong></div>
          <div v-if="r.arriveeLe" class="info"><span>Arrivé le</span><strong>{{ dateHeure(r.arriveeLe) }}</strong></div>
          <div v-if="r.departLe" class="info"><span>Parti le</span><strong>{{ dateHeure(r.departLe) }}</strong></div>
          <p v-if="r.notes" class="text-body-2 mt-3 mb-0"><v-icon icon="mdi-note-text-outline" size="16" /> {{ r.notes }}</p>
          <p v-if="r.motifAnnulation" class="text-body-2 mt-3 mb-0 text-error">{{ r.motifAnnulation }}</p>
        </v-card>
        <v-card class="classroom-card pa-5 mb-4">
          <div class="kpi-label mb-2">Client</div>
          <div class="text-body-1 font-weight-bold">{{ r.clientNom }}</div>
          <div class="text-body-2 text-medium-emphasis mb-3">{{ r.clientTelephone || 'Pas de téléphone' }}</div>
          <v-btn size="small" variant="tonal" prepend-icon="mdi-card-account-details-outline" :to="`/clients/${r.clientId}`">Fiche client</v-btn>
        </v-card>
        <v-card v-if="bail" class="classroom-card pa-5 mb-4">
          <div class="kpi-label mb-2">Bail</div>
          <div class="text-body-1 font-weight-bold">{{ bail.reference }}</div>
          <div class="text-body-2 text-medium-emphasis mb-3">Loyers facturés par échéance : le départ ne les refacture pas.</div>
          <v-btn size="small" variant="tonal" prepend-icon="mdi-file-sign" :to="`/baux/${bail.id}`">Échéancier</v-btn>
        </v-card>
        <v-card class="classroom-card pa-5 mb-4">
          <div class="d-flex align-center mb-2"><div class="kpi-label">Véhicules</div><v-spacer />
            <v-btn v-if="exploitant && ['OPTION', 'CONFIRMEE', 'ARRIVEE'].includes(r.statut)" size="x-small" variant="tonal" prepend-icon="mdi-plus" @click="veh.ouvert = !veh.ouvert">Véhicule</v-btn></div>
          <div v-for="v in vehicules" :key="v.id" class="vehicule-ligne">
            <span class="vehicule-plaque">{{ v.immatriculation }}</span>
            <span class="text-caption text-medium-emphasis">{{ [v.marque, v.couleur, v.placeNumero ? `place ${v.placeNumero}` : null].filter(Boolean).join(' · ') }}{{ v.sortieLe ? ' · sorti' : '' }}</span>
            <v-spacer /><v-btn v-if="!v.sortieLe && exploitant" size="x-small" variant="text" @click="sortirVehicule(v)">Sortie</v-btn>
          </div>
          <p v-if="!vehicules.length && !veh.ouvert" class="text-body-2 text-medium-emphasis mb-0">Aucun véhicule.</p>
          <div v-if="veh.ouvert" class="mt-2">
            <v-text-field v-model="veh.immatriculation" label="Plaque *" density="compact" />
            <v-row dense><v-col cols="6"><v-text-field v-model="veh.marque" label="Marque" density="compact" /></v-col>
              <v-col cols="6"><v-text-field v-model="veh.couleur" label="Couleur" density="compact" /></v-col></v-row>
            <v-select v-model="veh.placeId" :items="placesLibres.map(p => ({ value: p.id, title: `Place ${p.numero}` }))" label="Place" clearable density="compact" />
            <v-btn size="small" color="primary" variant="flat" block :disabled="!veh.immatriculation.trim()" @click="ajouterVehicule">Enregistrer</v-btn>
          </div>
        </v-card>
      </v-col>

      <v-col cols="12" md="8">
        <v-card class="classroom-card mb-4">
          <v-card-title class="d-flex align-center text-subtitle-1 font-weight-bold">
            Folio
            <v-spacer />
            <v-btn v-if="exploitant && ['CONFIRMEE', 'ARRIVEE'].includes(r.statut)" size="small" variant="tonal" prepend-icon="mdi-plus" @click="ouvrirExtra">
              Extra
            </v-btn>
          </v-card-title>
          <v-table density="comfortable">
            <thead><tr><th>Désignation</th><th class="text-right">Qté</th><th class="text-right">Prix HT</th><th class="text-right">Montant HT</th><th /></tr></thead>
            <tbody>
              <tr v-for="(l, i) in folio!.lignes" :key="l.id ?? `h${i}`">
                <td>
                  <v-icon :icon="l.nature === 'HEBERGEMENT' ? 'mdi-bed-outline' : 'mdi-food-fork-drink'" size="16" class="mr-1" />{{ l.designation }}
                  <div v-if="l.nature === 'EXTRA'" class="text-caption text-medium-emphasis">{{ dateCourte(l.date) }}</div>
                </td>
                <td class="text-right">{{ Number(l.quantite) }}</td>
                <td class="text-right">{{ usd(l.prixUnitaire) }}</td>
                <td class="text-right">{{ usd(l.montantHt) }}</td>
                <td class="text-right">
                  <v-btn v-if="l.id && exploitant && r.statut !== 'PARTIE'" icon="mdi-close" size="x-small" variant="text" aria-label="Retirer" @click="retirerExtra(l)" />
                </td>
              </tr>
            </tbody>
          </v-table>
          <div class="totaux pa-4">
            <div><span>Total HT</span><strong>{{ usd(folio!.totalHt) }}</strong></div>
            <div><span>TVA ({{ Number(folio!.tauxTva) }} %)</span><strong>{{ usd(folio!.totalTva) }}</strong></div>
            <div class="totaux__ttc"><span>Total TTC</span><strong>{{ usd(folio!.totalTtc) }}</strong></div>
            <div><span>Acomptes reçus</span><strong>− {{ usd(folio!.acomptes) }}</strong></div>
            <div class="totaux__reste"><span>Reste à payer</span><strong>{{ usd(folio!.resteAPayer) }}</strong></div>
            <div v-if="Number(folio!.cautions) > 0"><span>Caution détenue</span><strong>{{ usd(folio!.cautions) }}</strong></div>
          </div>
        </v-card>

        <v-card class="classroom-card">
          <v-card-title class="d-flex align-center flex-wrap ga-2 text-subtitle-1 font-weight-bold">
            Acomptes et cautions
            <v-spacer />
            <template v-if="encaisseur && ['OPTION', 'CONFIRMEE', 'ARRIVEE'].includes(r.statut)">
              <v-btn size="small" variant="tonal" prepend-icon="mdi-cash-plus" @click="ouvrirFonds('ACOMPTE')">Acompte</v-btn>
              <v-btn size="small" variant="tonal" prepend-icon="mdi-shield-lock-outline" @click="ouvrirFonds('CAUTION')">Caution</v-btn>
            </template>
            <v-btn v-if="encaisseur && Number(folio!.cautions) > 0" size="small" variant="text" prepend-icon="mdi-cash-refund" @click="ouvrirFonds('RESTITUTION')">Rendre la caution</v-btn>
            <v-btn v-if="encaisseur && Number(folio!.acomptes) > 0 && ['ANNULEE', 'NO_SHOW'].includes(r.statut)" size="small" variant="text"
              prepend-icon="mdi-cash-refund" @click="ouvrirFonds('REMB_ACOMPTE')">Rembourser l'acompte</v-btn>
          </v-card-title>
          <v-list v-if="folio!.fonds.length" density="compact">
            <v-list-item v-for="f in folio!.fonds" :key="f.id">
              <v-list-item-title>{{ libelleFonds[f.type] }} · {{ usd(f.montant) }}</v-list-item-title>
              <v-list-item-subtitle>{{ f.mode }} · pièce {{ f.pieceReference }} · {{ dateHeure(f.creeLe) }} · {{ f.creePar }}</v-list-item-subtitle>
            </v-list-item>
          </v-list>
          <v-card-text v-else class="text-medium-emphasis">Aucun acompte ni caution.</v-card-text>
        </v-card>

        <v-card v-if="logement" class="classroom-card mt-4">
          <v-card-title class="text-subtitle-1 font-weight-bold">États des lieux</v-card-title>
          <v-card-text><EtatDesLieux :reservation-id="Number(id)" :peut="exploitant" @retenue="(m: number) => retenueProposee = m" /></v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <!-- Arrivée -->
    <v-dialog v-model="arrivee.ouvert" max-width="480">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">Arrivée de {{ r.clientNom }}</v-card-title>
        <v-card-text>
          <p class="text-body-2 mb-4">Unité {{ r.uniteNumero }}. Vérifiez la pièce d'identité (fiche client) avant de remettre la clé.</p>
          <v-text-field v-model="arrivee.code" label="Code d'accès ou n° de clé (facultatif)" prepend-inner-icon="mdi-key-outline" />
          <v-checkbox v-model="arrivee.forcer" label="L'unité n'est pas encore propre : faire entrer quand même" hide-details density="compact" />
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="arrivee.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="action === 'arrivee'" @click="arriver">Enregistrer l'arrivée</v-btn></v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Modification -->
    <v-dialog v-model="modif.ouvert" max-width="520">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">Modifier le séjour</v-card-title>
        <v-card-text>
          <v-row v-if="r.uniteDuree !== 'HEURE'" dense>
            <v-col cols="6"><v-text-field v-model="modif.dateArrivee" type="date" label="Arrivée" :disabled="r.statut === 'ARRIVEE'" /></v-col>
            <v-col cols="6"><v-text-field v-model="modif.dateDepart" type="date" label="Départ" /></v-col>
          </v-row>
          <v-text-field v-model.number="modif.prix" type="number" min="0" label="Prix (USD HT)" />
          <v-autocomplete v-model="modif.clientId" v-model:search="rechercheClient" :items="clientsTrouves" no-filter clearable
            :label="`Remplacer le client (actuel : ${r.clientNom})`" prepend-inner-icon="mdi-account-switch-outline" />
          <v-textarea v-model="modif.notes" label="Notes" rows="2" auto-grow />
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="modif.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="action === 'modif'" @click="enregistrerModif">Enregistrer</v-btn></v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Extra -->
    <v-dialog v-model="extra.ouvert" max-width="520">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">Ajouter un extra</v-card-title>
        <v-card-text>
          <v-autocomplete v-model="extra.articleId" :items="(ref_?.articles ?? []).map(a => ({ value: a.id, title: `${a.libelle} (${a.code})` }))"
            label="Article de M-FINAPP (minibar, restauration, blanchisserie…)" />
          <v-text-field v-model="extra.designation" label="Libellé sur le folio" />
          <v-row dense>
            <v-col cols="6"><v-text-field v-model.number="extra.quantite" type="number" min="1" label="Quantité" /></v-col>
            <v-col cols="6"><v-text-field v-model.number="extra.prix" type="number" min="0" label="Prix unitaire (USD HT)" /></v-col>
          </v-row>
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="extra.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="action === 'extra'" @click="ajouterExtra">Ajouter</v-btn></v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Acompte, caution, restitution -->
    <v-dialog v-model="fonds.ouvert" max-width="480">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">{{ TITRES_FONDS[fonds.type] }}</v-card-title>
        <v-card-text>
          <v-text-field v-model.number="fonds.montant" type="number" min="0" label="Montant (USD)" prepend-inner-icon="mdi-currency-usd" />
          <v-btn-toggle v-model="fonds.mode" mandatory divided variant="outlined" class="mb-4 flex-wrap">
            <v-btn v-for="m in MODES" :key="m.value" :value="m.value" :prepend-icon="m.icone">{{ m.title }}</v-btn>
          </v-btn-toggle>
          <v-select v-if="fonds.mode !== 'CAISSE'" v-model="fonds.etablissementId" :items="etablissementsMode(fonds.mode)" item-title="nom" item-value="id"
            :label="fonds.mode === 'BANQUE' ? 'Banque' : 'Opérateur'" />
          <p class="text-caption text-medium-emphasis">Écrit dans M-FINAPP à votre nom : pièce de trésorerie, compte
            {{ ['ACOMPTE', 'REMB_ACOMPTE'].includes(fonds.type) ? '4191 (acomptes reçus)' : '1651 (dépôts et cautions)' }}.</p>
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="fonds.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="action === 'fonds'" :disabled="!fonds.montant || (fonds.mode !== 'CAISSE' && !fonds.etablissementId)"
            @click="enregistrerFonds">Enregistrer</v-btn></v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Départ -->
    <v-dialog v-model="depart.ouvert" max-width="560" scrollable>
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">Départ et encaissement</v-card-title>
        <v-card-text>
          <div class="totaux mb-4">
            <div class="totaux__ttc"><span>Total du folio (TTC)</span><strong>{{ usd(folio!.totalTtc) }}</strong></div>
          </div>
          <v-btn-toggle v-model="depart.mode" mandatory divided variant="outlined" class="mb-4 flex-wrap">
            <v-btn v-for="m in MODES" :key="m.value" :value="m.value" :prepend-icon="m.icone">{{ m.title }}</v-btn>
            <v-btn value="CREDIT" prepend-icon="mdi-file-document-outline">Facturer (crédit)</v-btn>
          </v-btn-toggle>
          <v-select v-if="['BANQUE', 'MOBILE_MONEY'].includes(depart.mode)" v-model="depart.etablissementId" :items="etablissementsMode(depart.mode)"
            item-title="nom" item-value="id" :label="depart.mode === 'BANQUE' ? 'Banque' : 'Opérateur'" />
          <v-select v-if="avecStock" v-model="depart.entrepotId" :items="ref_?.entrepots ?? []" item-title="nom" item-value="id"
            label="Entrepôt de sortie des extras (minibar, bar…)" />
          <template v-if="depart.mode !== 'CREDIT'">
            <v-text-field v-if="Number(folio!.acomptes) > 0" v-model.number="depart.acompteImpute" type="number" min="0" :max="Number(folio!.acomptes)"
              label="Acompte imputé (USD)" :hint="`Acomptes reçus : ${usd(folio!.acomptes)}`" persistent-hint class="mb-2" />
            <template v-if="Number(folio!.cautions) > 0">
              <v-text-field v-model.number="depart.cautionRetenue" type="number" min="0" :max="Number(folio!.cautions)"
                label="Caution retenue pour le folio (USD)" :hint="`Caution détenue : ${usd(folio!.cautions)}`" persistent-hint class="mb-2" />
              <v-checkbox v-model="depart.restituerCaution" label="Rendre au client le reste de la caution" density="compact" hide-details />
            </template>
            <div class="totaux mt-4"><div class="totaux__reste"><span>À encaisser maintenant</span><strong>{{ usd(aEncaisser) }}</strong></div></div>
          </template>
          <v-alert v-else type="info" variant="tonal" density="compact" class="mt-2">
            Le folio devient une créance du client dans M-FINAPP (le client doit être lié à M-FINAPP depuis sa fiche).
          </v-alert>
          <p class="text-caption text-medium-emphasis mt-3">Le folio devient une vente M-FINAPP (TVA, comptabilité, stock des extras) enregistrée à votre nom.</p>
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="depart.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="action === 'depart'"
            :disabled="(['BANQUE', 'MOBILE_MONEY'].includes(depart.mode) && !depart.etablissementId) || (avecStock && !depart.entrepotId)"
            @click="partir">Valider le départ</v-btn></v-card-actions>
      </v-card>
    </v-dialog>
  </div>
  <v-alert v-else-if="erreur" type="error" variant="tonal">{{ erreur }}</v-alert>
  <div v-else class="d-flex justify-center pa-10"><v-progress-circular indeterminate color="primary" /></div>
</template>

<style scoped>
.vehicule-ligne { display: flex; align-items: center; gap: 8px; padding: 6px 0; border-bottom: 1px dashed var(--d-line-strong); }
.vehicule-plaque { padding: 3px 8px; border-radius: 6px; font-weight: 800; font-size: 0.78rem; letter-spacing: 0.06em; background: #fde047; color: #111; box-shadow: inset 0 0 0 1.5px #111; }
.info { display: flex; justify-content: space-between; gap: 12px; padding: 5px 0; font-size: 0.9rem; border-bottom: 1px dashed var(--hairline, #eef0ee); }
.info span { opacity: 0.7; }
.totaux { display: grid; gap: 4px; max-width: 360px; margin-left: auto; }
.totaux > div { display: flex; justify-content: space-between; font-size: 0.9rem; }
.totaux__ttc { font-size: 1rem !important; border-top: 1px solid var(--hairline, #e5e7eb); padding-top: 4px; }
.totaux__reste { font-size: 1.05rem !important; color: var(--color-primary); }
</style>
