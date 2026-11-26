<script setup lang="ts">
import { useEtablissementStore } from '~/stores/etablissement'
import { useSessionStore } from '~/stores/session'
import { MENAGE, MODES, dateHeure, heure, usd } from '~/utils/libelles'

/**
 * Motel : arrivée express (choisir l'unité, la formule, noter la plaque, encaisser d'avance : un seul écran) et parking
 * (places, véhicules présents). Le forfait par créneau (« Sieste 3 h », « Nuit motel ») se règle dans les tarifs.
 */
useHead({ title: 'Motel' })

interface Unite { id: number, etablissementId: number, etablissementNom: string, numero: string, typeNom: string, etatMenage: string,
  horsService: boolean, occupee: boolean, arriveePrevue: string | null }
interface Forfait { id: number, libelle: string, prix: number, dureeHeures: number, plageDebut: string | null, plageFin: string | null }
interface Place { id: number, etablissementId: number, etablissementNom: string, numero: string, couverte: boolean, uniteId: number | null,
  uniteNumero: string | null, notes: string | null, actif: boolean, vehiculeId: number | null, immatriculation: string | null, reservationReference: string | null }
interface Vehicule { id: number, reservationId: number, reservationReference: string, client: string, uniteNumero: string, immatriculation: string,
  marque: string | null, couleur: string | null, placeNumero: string | null, entreeLe: string }
interface Referentiel { banques: { id: number, nom: string }[], mobileMoney: { id: number, nom: string }[] }

const api = useApi()
const session = useSessionStore()
const etablissements = useEtablissementStore()
const onglet = ref<'express' | 'parking'>('express')
const unites = ref<Unite[]>([])
const places = ref<Place[]>([])
const vehicules = ref<Vehicule[]>([])
const ref_ = ref<Referentiel | null>(null)
const forfaits = ref<Forfait[]>([])
const prixHeure = ref<number | null>(null)
const prixNuit = ref<number | null>(null)
const erreur = ref('')
const succes = ref('')
const encaisse = computed(() => session.a('RECEPTIONNISTE', 'DIR_HEBERGEMENT'))
const direction = computed(() => session.a('DIR_HEBERGEMENT', 'ADMIN'))

async function charger() {
  try {
    const query = { etablissementId: etablissements.filtre }
    const [u, p, v] = await Promise.all([api<Unite[]>('/menage/unites', { query }), api<Place[]>('/parking/places', { query }),
      api<Vehicule[]>('/parking/vehicules', { query })])
    unites.value = u
    places.value = p
    vehicules.value = v
    erreur.value = ''
  } catch (e) { erreur.value = messageErreur(e) }
}
onMounted(async () => {
  await charger()
  try { ref_.value = await api<Referentiel>('/referentiel') } catch { ref_.value = null }
})

// ── Arrivée express ─────────────────────────────────────────────────────────────────────────────────────────
/** En vue « tous les établissements », seules les unités des motels (et des établissements mixtes) ; sinon celles de l'établissement choisi. */
const unitesMotel = computed(() => {
  if (etablissements.filtre != null) return unites.value
  const motels = new Set(etablissements.liste.filter(e => ['MOTEL', 'MIXTE'].includes(e.type)).map(e => e.id))
  return motels.size ? unites.value.filter(u => motels.has(u.etablissementId)) : unites.value
})
const libres = computed(() => unitesMotel.value.filter(u => !u.horsService && !u.occupee))
const ex = reactive({ uniteId: null as number | null, formule: 'CRENEAU' as 'CRENEAU' | 'HEURE' | 'NUIT', tarifId: null as number | null, heures: 2,
  clientNom: '', clientTelephone: '', immatriculation: '', marque: '', couleur: '', placeId: null as number | null, codeAcces: '',
  payer: true, mode: 'CAISSE', etablissementPaiement: null as number | null, enCours: false })
const unite = computed(() => unites.value.find(u => u.id === ex.uniteId))
async function choisirUnite(u: Unite) {
  ex.uniteId = u.id
  ex.tarifId = null
  forfaits.value = []
  prixHeure.value = null
  prixNuit.value = null
  try {
    const [f, h, n] = await Promise.all([
      api<Forfait[]>(`/unites/${u.id}/forfaits`),
      api<{ prix: number | null }>('/tarifs/propose', { query: { uniteId: u.id, uniteDuree: 'HEURE' } }).catch(() => ({ prix: null })),
      api<{ prix: number | null }>('/tarifs/propose', { query: { uniteId: u.id, uniteDuree: 'NUIT' } }).catch(() => ({ prix: null })),
    ])
    forfaits.value = f
    prixHeure.value = h.prix
    prixNuit.value = n.prix
    ex.formule = f.length ? 'CRENEAU' : h.prix != null ? 'HEURE' : 'NUIT'
    ex.tarifId = f[0]?.id ?? null
    const garage = places.value.find(p => p.uniteId === u.id && p.actif && !p.vehiculeId)
    ex.placeId = garage?.id ?? null
  } catch (e) { erreur.value = messageErreur(e) }
}
const montant = computed(() => {
  if (ex.formule === 'CRENEAU') return forfaits.value.find(f => f.id === ex.tarifId)?.prix ?? null
  if (ex.formule === 'HEURE') return prixHeure.value == null ? null : prixHeure.value * ex.heures
  return prixNuit.value
})
const placesLibres = computed(() => places.value.filter(p => p.actif && !p.vehiculeId && (!unite.value || p.etablissementId === unite.value.etablissementId)))
const etablissementsMode = computed(() => ex.mode === 'BANQUE' ? ref_.value?.banques ?? [] : ex.mode === 'MOBILE_MONEY' ? ref_.value?.mobileMoney ?? [] : [])
const pret = computed(() => !!ex.uniteId && (ex.formule !== 'CRENEAU' || !!ex.tarifId) && (!ex.payer || ex.mode === 'CAISSE' || !!ex.etablissementPaiement))

async function enregistrerExpress() {
  ex.enCours = true
  erreur.value = ''
  succes.value = ''
  try {
    const corps = {
      uniteId: ex.uniteId, uniteDuree: ex.formule, tarifId: ex.formule === 'CRENEAU' ? ex.tarifId : null, heures: ex.formule === 'HEURE' ? ex.heures : null,
      clientNom: ex.clientNom || null, clientTelephone: ex.clientTelephone || null, immatriculation: ex.immatriculation || null, marque: ex.marque || null,
      couleur: ex.couleur || null, placeId: ex.placeId, codeAcces: ex.codeAcces || null,
      paiementMode: ex.payer && encaisse.value ? ex.mode : null, paiementEtablissementId: ex.payer ? ex.etablissementPaiement : null,
    }
    let r: { reservation: { id: number }, message: string }
    try {
      r = await api('/motel/express', { method: 'POST', body: corps })
    } catch (e: any) {
      // Unité pas encore propre : la réception peut confirmer l'arrivée quand même.
      if (e?.response?.status === 409 && /pas encore propre/.test(e?.data?.message ?? '')
        && await useDialogues().confirmer({ titre: 'L’unité n’est pas encore propre', message: e.data.message, bouton: 'Accueillir quand même', nature: 'attention' })) {
        r = await api('/motel/express', { method: 'POST', body: { ...corps, forcer: true } })
      } else throw e
    }
    succes.value = r.message
    Object.assign(ex, { uniteId: null, tarifId: null, clientNom: '', clientTelephone: '', immatriculation: '', marque: '', couleur: '', placeId: null, codeAcces: '' })
    await charger()
  } catch (e) { erreur.value = messageErreur(e, "L'arrivée n'a pas été enregistrée.") } finally { ex.enCours = false }
}

// ── Parking ─────────────────────────────────────────────────────────────────────────────────────────────────
const occupees = computed(() => places.value.filter(p => p.vehiculeId).length)
async function sortir(v: Vehicule) {
  if (!(await useDialogues().confirmer({ titre: `Sortie du véhicule ${v.immatriculation} ?`, message: 'Sa place est libérée.', bouton: 'Enregistrer la sortie' }))) return
  try { await api(`/parking/vehicules/${v.id}/sortie`, { method: 'POST' }); await charger() } catch (e) { erreur.value = messageErreur(e) }
}
const pl = reactive({ ouvert: false, id: null as number | null, etablissementId: null as number | null, numero: '', couverte: false, uniteId: null as number | null, notes: '', actif: true })
function editerPlace(p?: Place) {
  Object.assign(pl, p ? { ouvert: true, id: p.id, etablissementId: p.etablissementId, numero: p.numero, couverte: p.couverte, uniteId: p.uniteId, notes: p.notes ?? '', actif: p.actif }
    : { ouvert: true, id: null, etablissementId: etablissements.filtre ?? etablissements.liste[0]?.id ?? null, numero: '', couverte: false, uniteId: null, notes: '', actif: true })
}
async function enregistrerPlace() {
  try {
    await api(pl.id ? `/parking/places/${pl.id}` : '/parking/places', { method: pl.id ? 'PUT' : 'POST',
      body: { etablissementId: pl.etablissementId, numero: pl.numero, couverte: pl.couverte, uniteId: pl.uniteId, notes: pl.notes || null, actif: pl.actif } })
    pl.ouvert = false
    await charger()
  } catch (e) { erreur.value = messageErreur(e) }
}
const unitesDeLEtab = computed(() => unites.value.filter(u => u.etablissementId === pl.etablissementId).map(u => ({ value: u.id, title: `${u.numero} — ${u.typeNom}` })))
</script>

<template>
  <div class="d-apparait">
    <div class="page-head">
      <div>
        <div class="page-eyebrow">Exploitation</div>
        <h1 class="page-title">Motel</h1>
        <p class="page-sub">Arrivée express en un écran, forfaits par créneau et parking</p>
      </div>
      <div class="onglets" role="tablist">
        <button type="button" role="tab" :aria-selected="onglet === 'express'" class="onglet" :class="{ 'onglet--actif': onglet === 'express' }" @click="onglet = 'express'">
          <v-icon icon="mdi-lightning-bolt-outline" size="17" />Arrivée express</button>
        <button type="button" role="tab" :aria-selected="onglet === 'parking'" class="onglet" :class="{ 'onglet--actif': onglet === 'parking' }" @click="onglet = 'parking'">
          <v-icon icon="mdi-car-outline" size="17" />Parking<span class="d-compteur ml-1">{{ occupees }}/{{ places.length }}</span></button>
      </div>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-4" @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" closable class="mb-4" @click:close="succes = ''">{{ succes }}</v-alert>

    <div v-if="onglet === 'express'" class="express">
      <section class="d-carte">
        <div class="d-section">
          <span class="d-icone d-icone--sm d-icone--plein"><span class="etape">1</span></span>
          <div><div class="d-section__titre">Unité</div><div class="d-section__sous">{{ libres.length }} libre(s) maintenant</div></div>
        </div>
        <div v-if="libres.length" class="unites-libres">
          <button v-for="u in libres" :key="u.id" type="button" class="libre" :class="{ 'libre--choisie': ex.uniteId === u.id, 'libre--sale': u.etatMenage !== 'PROPRE' }"
            :style="{ '--c': MENAGE[u.etatMenage]?.couleur }" @click="choisirUnite(u)">
            <span class="libre__numero">{{ u.numero }}</span>
            <span class="libre__type">{{ u.typeNom }}</span>
            <span class="libre__etat"><i />{{ MENAGE[u.etatMenage]?.libelle }}</span>
          </button>
        </div>
        <div v-else class="d-vide"><div class="d-vide__icone"><v-icon icon="mdi-bed-outline" size="28" /></div><div class="d-vide__titre">Complet</div>
          <div class="d-vide__texte">Aucune unité libre pour l’instant.</div></div>
      </section>

      <section class="d-carte" :class="{ 'grise': !ex.uniteId }">
        <div class="d-section">
          <span class="d-icone d-icone--sm d-icone--plein"><span class="etape">2</span></span>
          <div><div class="d-section__titre">Formule et client</div><div class="d-section__sous">{{ unite ? `Unité ${unite.numero} · ${unite.typeNom}` : 'Choisissez d’abord une unité' }}</div></div>
        </div>
        <div class="px-5 pb-5">
          <div class="formules mb-4">
            <button type="button" class="formule" :class="{ 'formule--active': ex.formule === 'CRENEAU' }" :disabled="!forfaits.length" @click="ex.formule = 'CRENEAU'">
              <v-icon icon="mdi-timer-sand" size="20" /><span>Forfait</span></button>
            <button type="button" class="formule" :class="{ 'formule--active': ex.formule === 'HEURE' }" @click="ex.formule = 'HEURE'">
              <v-icon icon="mdi-clock-outline" size="20" /><span>À l’heure</span></button>
            <button type="button" class="formule" :class="{ 'formule--active': ex.formule === 'NUIT' }" @click="ex.formule = 'NUIT'">
              <v-icon icon="mdi-weather-night" size="20" /><span>La nuit</span></button>
          </div>
          <div v-if="ex.formule === 'CRENEAU'" class="forfaits mb-4">
            <button v-for="f in forfaits" :key="f.id" type="button" class="forfait" :class="{ 'forfait--actif': ex.tarifId === f.id }" @click="ex.tarifId = f.id">
              <strong>{{ f.libelle }}</strong><span>{{ f.dureeHeures }} h<template v-if="f.plageDebut"> · {{ f.plageDebut.slice(0, 5) }}–{{ f.plageFin?.slice(0, 5) }}</template></span>
              <b>{{ usd(f.prix) }}</b>
            </button>
            <p v-if="ex.uniteId && !forfaits.length" class="text-body-2 text-medium-emphasis mb-0">Aucun forfait pour ce type d’unité : créez-en dans Établissements et tarifs (durée « Forfait »).</p>
          </div>
          <div v-else-if="ex.formule === 'HEURE'" class="d-flex align-center ga-3 mb-4">
            <v-btn icon="mdi-minus" variant="tonal" size="small" :disabled="ex.heures <= 1" aria-label="Une heure de moins" @click="ex.heures--" />
            <div class="heures"><b>{{ ex.heures }}</b> heure{{ ex.heures > 1 ? 's' : '' }}</div>
            <v-btn icon="mdi-plus" variant="tonal" size="small" :disabled="ex.heures >= 24" aria-label="Une heure de plus" @click="ex.heures++" />
            <span class="text-body-2 text-medium-emphasis">départ vers {{ heure(new Date(Date.now() + ex.heures * 3_600_000).toISOString()) }}</span>
          </div>
          <v-row dense>
            <v-col cols="12"><v-text-field v-model="ex.clientNom" label="Nom du client (facultatif)" prepend-inner-icon="mdi-account-outline" hide-details /></v-col>
            <v-col cols="6"><v-text-field v-model="ex.clientTelephone" label="Téléphone" inputmode="tel" prepend-inner-icon="mdi-phone-outline" hide-details /></v-col>
            <v-col cols="6"><v-text-field v-model="ex.codeAcces" label="Code ou clé" prepend-inner-icon="mdi-key-outline" hide-details /></v-col>
            <v-col cols="6"><v-text-field v-model="ex.immatriculation" label="Plaque" prepend-inner-icon="mdi-car-outline" hide-details class="plaque" /></v-col>
            <v-col cols="6"><v-select v-model="ex.placeId" :items="placesLibres.map(p => ({ value: p.id, title: `${p.numero}${p.couverte ? ' (couverte)' : ''}${p.uniteNumero ? ` · garage ${p.uniteNumero}` : ''}` }))"
              label="Place" clearable hide-details :disabled="!ex.immatriculation" /></v-col>
            <v-col cols="6"><v-text-field v-model="ex.marque" label="Marque" hide-details /></v-col>
            <v-col cols="6"><v-text-field v-model="ex.couleur" label="Couleur" hide-details /></v-col>
          </v-row>
        </div>
      </section>

      <aside class="d-carte recap" :class="{ 'grise': !ex.uniteId }">
        <div class="recap__tete">
          <div class="recap__libelle">À encaisser</div>
          <div class="recap__montant">{{ montant != null ? usd(montant) : '—' }}</div>
          <div class="recap__note">hors taxes · TVA ajoutée par M-FINAPP</div>
        </div>
        <div class="px-5 pb-5">
          <v-switch v-if="encaisse" v-model="ex.payer" color="primary" inset hide-details label="Payé d’avance" class="mb-2" />
          <template v-if="ex.payer && encaisse">
            <div class="modes mb-3">
              <button v-for="m in MODES" :key="m.value" type="button" class="mode" :class="{ 'mode--actif': ex.mode === m.value }" @click="ex.mode = m.value; ex.etablissementPaiement = null">
                <v-icon :icon="m.icone" size="18" /><span>{{ m.title.split(' ')[0] }}</span></button>
            </div>
            <v-select v-if="ex.mode !== 'CAISSE'" v-model="ex.etablissementPaiement" :items="etablissementsMode" item-title="nom" item-value="id"
              :label="ex.mode === 'BANQUE' ? 'Banque' : 'Opérateur'" hide-details class="mb-3" />
          </template>
          <p v-else-if="!encaisse" class="text-body-2 text-medium-emphasis">Votre rôle n’encaisse pas : le paiement se fera au départ.</p>
          <v-btn color="primary" variant="flat" size="x-large" block prepend-icon="mdi-lightning-bolt" :loading="ex.enCours" :disabled="!pret" @click="enregistrerExpress">
            Accueillir le client</v-btn>
          <p class="recap__aide">Réservation, arrivée{{ ex.immatriculation ? ', véhicule' : '' }}{{ ex.payer && encaisse ? ' et encaissement' : '' }} en un geste.</p>
        </div>
      </aside>
    </div>

    <div v-else class="parking">
      <section class="d-carte">
        <div class="d-section">
          <span class="d-icone d-icone--sm"><v-icon icon="mdi-parking" size="18" /></span>
          <div><div class="d-section__titre">Places</div><div class="d-section__sous">{{ occupees }} occupée(s) sur {{ places.length }}</div></div>
          <v-spacer /><v-btn v-if="direction" size="small" variant="tonal" prepend-icon="mdi-plus" @click="editerPlace()">Place</v-btn>
        </div>
        <div v-if="places.length" class="places">
          <button v-for="p in places" :key="p.id" type="button" class="place" :class="{ 'place--occupee': p.vehiculeId, 'place--inactive': !p.actif }"
            :disabled="!direction" @click="editerPlace(p)">
            <span class="place__numero">{{ p.numero }}</span>
            <span class="place__info">{{ p.vehiculeId ? p.immatriculation : p.actif ? 'Libre' : 'Fermée' }}</span>
            <span v-if="p.uniteNumero" class="place__garage">garage {{ p.uniteNumero }}</span>
            <v-icon v-if="p.couverte" icon="mdi-home-roof" size="14" class="place__toit" />
          </button>
        </div>
        <div v-else class="d-vide"><div class="d-vide__icone"><v-icon icon="mdi-parking" size="28" /></div><div class="d-vide__titre">Aucune place</div>
          <div class="d-vide__texte">Créez les places de parking (et les garages attitrés des unités) : l’arrivée express y range les véhicules.</div></div>
      </section>
      <section class="d-carte">
        <div class="d-section">
          <span class="d-icone d-icone--sm" style="--c: #2563eb"><v-icon icon="mdi-car-multiple" size="18" /></span>
          <div><div class="d-section__titre">Véhicules présents</div><div class="d-section__sous">Sortie enregistrée d’office au départ du client</div></div>
          <v-spacer /><span class="d-compteur">{{ vehicules.length }}</span>
        </div>
        <div v-if="vehicules.length" class="vehicules">
          <div v-for="v in vehicules" :key="v.id" class="vehicule">
            <span class="vehicule__plaque">{{ v.immatriculation }}</span>
            <div class="vehicule__texte"><strong>{{ v.client }}</strong><small>Unité {{ v.uniteNumero }}{{ v.placeNumero ? ` · place ${v.placeNumero}` : '' }}{{ v.marque ? ` · ${v.marque}` : '' }}{{ v.couleur ? ` ${v.couleur}` : '' }} · entré {{ dateHeure(v.entreeLe) }}</small></div>
            <v-btn size="small" variant="tonal" prepend-icon="mdi-exit-run" @click="sortir(v)">Sortie</v-btn>
          </div>
        </div>
        <div v-else class="d-vide"><div class="d-vide__icone"><v-icon icon="mdi-car-off" size="28" /></div><div class="d-vide__titre">Aucun véhicule</div></div>
      </section>
    </div>

    <v-dialog v-model="pl.ouvert" max-width="480">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">{{ pl.id ? `Place ${pl.numero}` : 'Nouvelle place' }}</v-card-title>
        <v-card-text>
          <v-select v-if="etablissements.liste.length > 1" v-model="pl.etablissementId" :items="etablissements.liste" item-title="nom" item-value="id" label="Établissement" />
          <v-text-field v-model="pl.numero" label="Numéro *" />
          <v-select v-model="pl.uniteId" :items="unitesDeLEtab" label="Garage attitré de l’unité (facultatif)" clearable />
          <v-text-field v-model="pl.notes" label="Notes" />
          <v-switch v-model="pl.couverte" color="primary" inset hide-details label="Place couverte" />
          <v-switch v-model="pl.actif" color="primary" inset hide-details label="Place ouverte" />
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="pl.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :disabled="!pl.numero.trim() || !pl.etablissementId" @click="enregistrerPlace">Enregistrer</v-btn></v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.onglets { display: inline-flex; gap: 4px; padding: 4px; border-radius: 14px; background: var(--d-surface-3); }
.onglet { display: inline-flex; align-items: center; gap: 6px; height: 38px; padding: 0 14px; border: 0; border-radius: 11px; cursor: pointer;
  font: inherit; font-size: 0.88rem; font-weight: 650; color: var(--d-ink-soft); background: transparent; }
.onglet--actif { color: var(--d-ink); background: var(--d-surface); box-shadow: var(--d-shadow-1); }

.express { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.3fr) minmax(280px, 0.8fr); gap: 16px; align-items: start; }
.etape { font-weight: 800; font-size: 0.85rem; }
.grise { opacity: 0.55; pointer-events: none; }
.unites-libres { display: grid; grid-template-columns: repeat(auto-fill, minmax(92px, 1fr)); gap: 8px; padding: 8px 18px 18px; }
.libre { display: flex; flex-direction: column; gap: 2px; padding: 12px; border-radius: 16px; text-align: left; cursor: pointer; font: inherit; color: inherit;
  background: var(--d-surface-2); border: 1px solid var(--d-line); transition: all 0.16s; }
.libre:hover { transform: translateY(-2px); box-shadow: var(--d-shadow-2); }
.libre--choisie { color: #fff; background: var(--d-grad); border-color: transparent; box-shadow: 0 10px 22px -12px color-mix(in srgb, var(--color-primary) 90%, transparent); }
.libre__numero { font-size: 1.35rem; font-weight: 800; letter-spacing: -0.04em; font-variant-numeric: tabular-nums; }
.libre__type { font-size: 0.72rem; opacity: 0.75; }
.libre__etat { display: inline-flex; align-items: center; gap: 5px; font-size: 0.7rem; font-weight: 650; margin-top: 4px; }
.libre__etat i { width: 6px; height: 6px; border-radius: 50%; background: var(--c); }
.libre--choisie .libre__etat i { background: #fff; }

.formules { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.formule { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 12px; border-radius: 14px; cursor: pointer; font: inherit;
  font-size: 0.82rem; font-weight: 650; color: var(--d-ink-2); background: var(--d-surface-2); border: 1px solid var(--d-line); }
.formule:disabled { opacity: 0.45; cursor: default; }
.formule--active { color: var(--color-primary); border-color: var(--color-primary); background: color-mix(in srgb, var(--color-primary) 8%, var(--d-surface)); }
.forfaits { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 8px; }
.forfait { display: flex; flex-direction: column; gap: 2px; padding: 12px 14px; border-radius: 14px; text-align: left; cursor: pointer; font: inherit; color: inherit;
  background: var(--d-surface); border: 1px solid var(--d-line); }
.forfait span { font-size: 0.76rem; color: var(--d-ink-faint); }
.forfait b { font-size: 1.05rem; margin-top: 4px; font-variant-numeric: tabular-nums; }
.forfait--actif { border-color: var(--color-primary); box-shadow: 0 0 0 3px color-mix(in srgb, var(--color-primary) 14%, transparent); }
.heures { font-size: 0.95rem; min-width: 84px; text-align: center; }
.heures b { font-size: 1.5rem; font-variant-numeric: tabular-nums; }
.plaque :deep(input) { text-transform: uppercase; font-weight: 700; letter-spacing: 0.06em; }

.recap { position: sticky; top: 84px; overflow: hidden; }
.recap__tete { padding: 22px 20px 18px; color: #fff; background: var(--d-grad-hero); }
.recap__libelle { font-size: 0.78rem; font-weight: 650; opacity: 0.8; text-transform: uppercase; letter-spacing: 0.08em; }
.recap__montant { font-size: 2.3rem; font-weight: 800; letter-spacing: -0.05em; font-variant-numeric: tabular-nums; margin-top: 4px; }
.recap__note { font-size: 0.76rem; opacity: 0.75; }
.recap .px-5 { padding-top: 16px; }
.recap__aide { font-size: 0.76rem; color: var(--d-ink-faint); text-align: center; margin: 10px 0 0; }
.modes { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.mode { display: flex; flex-direction: column; align-items: center; gap: 3px; padding: 10px 4px; border-radius: 12px; cursor: pointer; font: inherit;
  font-size: 0.74rem; font-weight: 650; color: var(--d-ink-2); background: var(--d-surface-2); border: 1px solid var(--d-line); }
.mode--actif { color: var(--color-primary); border-color: var(--color-primary); background: color-mix(in srgb, var(--color-primary) 8%, var(--d-surface)); }

.parking { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 16px; align-items: start; }
.places { display: grid; grid-template-columns: repeat(auto-fill, minmax(104px, 1fr)); gap: 8px; padding: 8px 18px 18px; }
.place { position: relative; display: flex; flex-direction: column; gap: 2px; padding: 12px; min-height: 84px; border-radius: 14px; text-align: left; font: inherit; color: inherit;
  cursor: pointer; background: color-mix(in srgb, var(--menage-propre) 6%, var(--d-surface)); border: 1.5px dashed color-mix(in srgb, var(--menage-propre) 40%, transparent); }
.place:disabled { cursor: default; }
.place--occupee { background: var(--d-surface-3); border: 1.5px solid var(--d-line-strong); }
.place--inactive { opacity: 0.5; }
.place__numero { font-weight: 800; font-size: 1.1rem; letter-spacing: -0.03em; }
.place__info { font-size: 0.76rem; font-weight: 650; color: var(--d-ink-2); text-transform: uppercase; letter-spacing: 0.03em; }
.place__garage { font-size: 0.68rem; color: var(--d-ink-faint); }
.place__toit { position: absolute; top: 10px; right: 10px; color: var(--d-ink-faint); }
.vehicules { padding: 4px 14px 14px; }
.vehicule { display: flex; align-items: center; gap: 12px; padding: 10px 4px; border-bottom: 1px dashed var(--d-line-strong); }
.vehicule:last-child { border-bottom: 0; }
.vehicule__plaque { padding: 6px 10px; border-radius: 8px; font-weight: 800; font-size: 0.82rem; letter-spacing: 0.08em; font-variant-numeric: tabular-nums;
  background: #fde047; color: #111; box-shadow: inset 0 0 0 2px #111; }
.vehicule__texte { flex: 1; min-width: 0; }
.vehicule__texte strong { display: block; font-size: 0.88rem; }
.vehicule__texte small { display: block; font-size: 0.76rem; color: var(--d-ink-faint); }

@media (max-width: 1279px) { .express { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); } .recap { grid-column: 1 / -1; position: static; } }
@media (max-width: 959px) { .express, .parking { grid-template-columns: 1fr; } }
</style>
