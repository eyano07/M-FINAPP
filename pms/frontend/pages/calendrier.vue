<script setup lang="ts">
import { useEtablissementStore } from '~/stores/etablissement'
import { useSessionStore } from '~/stores/session'
import { MENAGE, STATUTS, jourIso } from '~/utils/libelles'

/**
 * Calendrier centralisé : chaque unité (chambre, unité de motel, appartement) sur une ligne, les jours en colonnes.
 * Une barre par réservation, colorée par statut ; glisser une barre la déplace (autre unité, autres dates : la base
 * refuse toute surréservation) ; cliquer une case vide ouvre une réservation sur cette unité et ce jour.
 */
useHead({ title: 'Calendrier' })

interface Unite { id: number, etablissementId: number, etablissementNom: string, typeNom: string, numero: string, etatMenage: string, horsService: boolean, actif: boolean,
  motifHorsService: string | null, horsServiceDu: string | null, horsServiceAu: string | null }
interface Etab { id: number, nom: string }
interface Resa { id: number, reference: string, uniteId: number, clientNom: string, debut: string, fin: string, statut: string, uniteDuree: string }

const api = useApi()
const session = useSessionStore()
const peutReserver = computed(() => session.a('RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN'))
const etablissements = ref<Etab[]>([])
// Établissement de travail choisi à la connexion (changeable ici aussi).
const etabStore = useEtablissementStore()
const etablissementId = computed<number | null>({ get: () => etabStore.choisi ?? null, set: v => etabStore.choisir(v) })
const unites = ref<Unite[]>([])
const resas = ref<Resa[]>([])
const nbJours = ref(14)
const debut = ref(lundi(new Date()))
const erreur = ref('')
const message = ref('')
const nouvelle = reactive({ ouvert: false, uniteId: null as number | null, date: null as string | null })
const LARGEUR = 64

function lundi(d: Date) {
  const r = new Date(d.getFullYear(), d.getMonth(), d.getDate())
  r.setDate(r.getDate() - ((r.getDay() + 6) % 7))
  return r
}
const jours = computed(() => Array.from({ length: nbJours.value }, (_, i) => {
  const d = new Date(debut.value)
  d.setDate(d.getDate() + i)
  return d
}))
const fin = computed(() => { const d = new Date(debut.value); d.setDate(d.getDate() + nbJours.value); return d })
const aujourdhui = jourIso(new Date())

async function charger() {
  try {
    const [u, r] = await Promise.all([
      api<Unite[]>('/unites', { query: { etablissementId: etablissementId.value ?? undefined } }),
      api<Resa[]>('/reservations', { query: { du: debut.value.toISOString(), au: fin.value.toISOString(), etablissementId: etablissementId.value ?? undefined } }),
    ])
    unites.value = u.filter(x => x.actif)
    resas.value = r.filter(x => !['ANNULEE', 'NO_SHOW'].includes(x.statut))
    erreur.value = ''
  } catch (e) {
    erreur.value = messageErreur(e, 'Calendrier indisponible.')
  }
}
let minuterie: ReturnType<typeof setInterval> | null = null
onMounted(async () => {
  try { etablissements.value = await api<Etab[]>('/etablissements') } catch { /* liste facultative */ }
  await charger()
  minuterie = setInterval(charger, 20_000)
})
onBeforeUnmount(() => { if (minuterie) clearInterval(minuterie) })
watch([debut, nbJours, etablissementId], charger)

function decaler(n: number) {
  const d = new Date(debut.value)
  d.setDate(d.getDate() + n)
  debut.value = d
}

/** Position d'une barre : de la mi-journée d'arrivée à la mi-journée de départ (comme sur un planning d'hôtel). */
function barre(r: Resa) {
  const jour = 86_400_000
  const origine = debut.value.getTime()
  const d = new Date(r.debut)
  const f = new Date(r.fin)
  const iDebut = Math.round((new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() - origine) / jour)
  const iFin = Math.round((new Date(f.getFullYear(), f.getMonth(), f.getDate()).getTime() - origine) / jour)
  const gauche = Math.max(0, iDebut + 0.5)
  const droite = Math.min(nbJours.value, Math.max(iFin + 0.5, iDebut + 1))
  if (droite <= 0 || gauche >= nbJours.value) return null
  return { left: `${gauche * LARGEUR}px`, width: `${Math.max(0.5, droite - gauche) * LARGEUR - 4}px` }
}
/** Bande « hors service » : de la mise hors service à la fin prévue des travaux (sans fin : jusqu'au bout de la vue). */
function bandeHs(u: Unite) {
  if (!u.horsService) return null
  const jour = 86_400_000
  const origine = debut.value.getTime()
  const d = u.horsServiceDu ? new Date(u.horsServiceDu) : new Date()
  const iDebut = Math.floor((new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() - origine) / jour)
  let iFin = nbJours.value
  if (u.horsServiceAu) {
    const f = new Date(u.horsServiceAu)
    iFin = Math.ceil((new Date(f.getFullYear(), f.getMonth(), f.getDate()).getTime() - origine) / jour) + 1
  }
  const gauche = Math.max(0, iDebut)
  const droite = Math.min(nbJours.value, iFin)
  if (droite <= 0 || gauche >= nbJours.value) return null
  return { left: `${gauche * LARGEUR}px`, width: `${(droite - gauche) * LARGEUR}px` }
}
/** Case de la vue bloquée par une mise hors service datée. */
function caseBloquee(u: Unite, j: Date) {
  if (!u.horsService) return false
  const debutJour = new Date(j.getFullYear(), j.getMonth(), j.getDate()).getTime()
  const du = u.horsServiceDu ? new Date(u.horsServiceDu).getTime() : 0
  const au = u.horsServiceAu ? new Date(u.horsServiceAu).getTime() : Number.POSITIVE_INFINITY
  return debutJour + 86_400_000 > du && debutJour < au
}
const resasParUnite = computed(() => {
  const m = new Map<number, Resa[]>()
  for (const r of resas.value) {
    if (!m.has(r.uniteId)) m.set(r.uniteId, [])
    m.get(r.uniteId)!.push(r)
  }
  return m
})

const groupes = computed(() => {
  const g = new Map<string, Unite[]>()
  for (const u of unites.value) {
    if (!g.has(u.etablissementNom)) g.set(u.etablissementNom, [])
    g.get(u.etablissementNom)!.push(u)
  }
  return [...g.entries()]
})

// ── Glisser-déposer ─────────────────────────────────────────────────────
const glisse = ref<Resa | null>(null)
function commencer(r: Resa, ev: DragEvent) {
  if (!peutReserver.value || ['PARTIE'].includes(r.statut)) { ev.preventDefault(); return }
  glisse.value = r
  ev.dataTransfer?.setData('text/plain', String(r.id))
}
async function deposer(u: Unite, jour: Date) {
  const r = glisse.value
  glisse.value = null
  if (!r) return
  message.value = ''
  try {
    const corps: Record<string, unknown> = { uniteId: u.id }
    if (r.uniteDuree !== 'HEURE' && r.statut !== 'ARRIVEE') {
      const d = new Date(r.debut)
      const f = new Date(r.fin)
      const nuits = Math.round((new Date(f.getFullYear(), f.getMonth(), f.getDate()).getTime()
        - new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()) / 86_400_000)
      const depart = new Date(jour)
      depart.setDate(depart.getDate() + Math.max(1, nuits))
      corps.dateArrivee = jourIso(jour)
      corps.dateDepart = jourIso(depart)
    }
    await api(`/reservations/${r.id}`, { method: 'PUT', body: corps })
    message.value = `${r.reference} déplacée vers l'unité ${u.numero}.`
    await charger()
  } catch (e) {
    erreur.value = messageErreur(e, 'Déplacement refusé.')
  }
}

function caseVide(u: Unite, jour: Date) {
  if (!peutReserver.value || caseBloquee(u, jour)) return
  Object.assign(nouvelle, { ouvert: true, uniteId: u.id, date: jourIso(jour) })
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <div class="page-eyebrow">Exploitation</div>
        <h1 class="page-title">Calendrier</h1>
        <p class="page-sub">Occupation en temps réel — glissez une réservation pour la déplacer</p>
      </div>
      <div class="d-flex flex-wrap ga-2 align-center">
        <v-select v-if="etablissements.length > 1" v-model="etablissementId" :items="[{ id: null, nom: 'Tous les établissements' }, ...etablissements]"
          item-title="nom" item-value="id" density="compact" hide-details style="min-width: 220px" />
        <v-btn-toggle v-model="nbJours" density="compact" mandatory variant="outlined" divided>
          <v-btn :value="7">7 j</v-btn><v-btn :value="14">14 j</v-btn><v-btn :value="31">31 j</v-btn>
        </v-btn-toggle>
        <v-btn icon="mdi-chevron-left" variant="text" aria-label="Période précédente" @click="decaler(-7)" />
        <v-btn variant="tonal" @click="debut = lundi(new Date())">Aujourd'hui</v-btn>
        <v-btn icon="mdi-chevron-right" variant="text" aria-label="Période suivante" @click="decaler(7)" />
        <v-btn v-if="peutReserver" color="primary" prepend-icon="mdi-plus" @click="Object.assign(nouvelle, { ouvert: true, uniteId: null, date: null })">
          Réserver
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-3" @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="message" type="success" variant="tonal" closable class="mb-3" @click:close="message = ''">{{ message }}</v-alert>

    <div class="legende mb-3">
      <span v-for="(s, cle) in STATUTS" v-show="!['ANNULEE', 'NO_SHOW'].includes(cle as string)" :key="cle" class="pastille" :style="{ '--c': s.couleur }">{{ s.libelle }}</span>
    </div>

    <v-card class="classroom-card planning">
      <div class="planning__defile">
        <div class="planning__grille" :style="{ '--jour': LARGEUR + 'px', '--jours': nbJours }">
          <div class="planning__entete">
            <div class="planning__coin">Unité</div>
            <div v-for="j in jours" :key="j.toISOString()" class="planning__jour"
              :class="{ 'planning__jour--aujourdhui': jourIso(j) === aujourdhui, 'planning__jour--we': [0, 6].includes(j.getDay()) }">
              <span>{{ j.toLocaleDateString('fr-FR', { weekday: 'short' }) }}</span>
              <strong>{{ j.getDate() }}</strong>
            </div>
          </div>
          <template v-for="[etab, liste] in groupes" :key="etab">
            <div class="planning__groupe">{{ etab }}</div>
            <div v-for="u in liste" :key="u.id" class="planning__ligne">
              <div class="planning__unite" :title="u.typeNom">
                <strong>{{ u.numero }}</strong>
                <span class="text-caption text-medium-emphasis">{{ u.typeNom }}</span>
                <v-icon v-if="u.horsService" icon="mdi-wrench-outline" size="16" color="error" title="Hors service" />
                <v-icon v-else :icon="MENAGE[u.etatMenage]?.icone" size="16" :style="{ color: MENAGE[u.etatMenage]?.couleur }"
                  :title="MENAGE[u.etatMenage]?.libelle" />
              </div>
              <div class="planning__piste">
                <div v-if="bandeHs(u)" class="planning__hs" :style="bandeHs(u)!" :title="`Hors service${u.motifHorsService ? ' : ' + u.motifHorsService : ''}`">
                  <v-icon icon="mdi-wrench-outline" size="14" /><span>{{ u.motifHorsService || 'Hors service' }}</span>
                </div>
                <div v-for="j in jours" :key="j.toISOString()" class="planning__case"
                  :class="{ 'planning__case--we': [0, 6].includes(j.getDay()), 'planning__case--aujourdhui': jourIso(j) === aujourdhui }"
                  @click="caseVide(u, j)" @dragover.prevent @drop.prevent="deposer(u, j)" />
                <template v-for="r in resasParUnite.get(u.id) ?? []" :key="r.id">
                  <div v-if="barre(r)" class="planning__barre" :style="{ ...barre(r), '--c': STATUTS[r.statut]?.couleur }"
                    :draggable="peutReserver && r.statut !== 'PARTIE'" :title="`${r.reference} — ${r.clientNom}`"
                    @dragstart="commencer(r, $event)" @click.stop="navigateTo(`/reservations/${r.id}`)">
                    {{ r.clientNom }}
                  </div>
                </template>
              </div>
            </div>
          </template>
          <div v-if="!unites.length" class="pa-6 text-medium-emphasis">
            Aucune unité. <NuxtLink v-if="session.a('DIR_HEBERGEMENT', 'ADMIN')" to="/parametrage">Créez vos établissements et unités</NuxtLink>.
          </div>
        </div>
      </div>
    </v-card>

    <ReservationNouvelle v-model="nouvelle.ouvert" :unite-id="nouvelle.uniteId" :date-arrivee="nouvelle.date" @creee="charger" />
  </div>
</template>

<style scoped>
.legende { display: flex; flex-wrap: wrap; gap: 8px; }
.planning { overflow: hidden; }
.planning__defile { overflow-x: auto; }
.planning__grille { min-width: calc(150px + var(--jour) * var(--jours)); }
.planning__entete, .planning__ligne { display: grid; grid-template-columns: 150px 1fr; }
.planning__entete { position: sticky; top: 0; z-index: 2; background: var(--surface, #fff); border-bottom: 1px solid var(--hairline, #e5e7eb); }
.planning__entete { grid-template-columns: 150px repeat(var(--jours), var(--jour)); }
.planning__coin { padding: 10px 12px; font-weight: 700; font-size: 0.8rem; }
.planning__jour { display: flex; flex-direction: column; align-items: center; gap: 2px; padding: 8px 0; font-size: 0.68rem; font-weight: 600;
  letter-spacing: 0.04em; text-transform: uppercase; color: var(--d-ink-faint); }
.planning__jour strong { font-size: 0.95rem; font-weight: 750; letter-spacing: -0.02em; color: var(--d-ink); width: 30px; height: 30px; border-radius: 50%;
  display: grid; place-items: center; font-variant-numeric: tabular-nums; }
.planning__jour--aujourdhui strong { color: #fff; background: var(--d-grad); box-shadow: 0 6px 14px -8px color-mix(in srgb, var(--color-primary) 90%, transparent); }
.planning__jour--we { background: color-mix(in srgb, var(--color-primary) 4%, transparent); }
.planning__jour--aujourdhui { color: var(--color-primary); }
.planning__groupe { padding: 10px 14px; font-size: 0.7rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.09em; color: var(--d-ink-faint);
  background: var(--d-surface-2); border-bottom: 1px solid var(--hairline); }
.planning__ligne { border-bottom: 1px solid var(--hairline, #eef0ee); min-height: 50px; }
.planning__ligne:hover .planning__unite { background: var(--d-surface-2); }
.planning__unite { display: flex; align-items: center; gap: 8px; padding: 6px 14px; position: sticky; left: 0; z-index: 1;
  background: var(--surface, #fff); border-right: 1px solid var(--hairline, #eef0ee); transition: background 0.16s; }
.planning__unite strong { font-weight: 760; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
.planning__piste { position: relative; display: grid; grid-template-columns: repeat(var(--jours), var(--jour)); }
.planning__hs { position: absolute; top: 0; bottom: 0; z-index: 0; display: flex; align-items: center; gap: 6px; padding: 0 10px; overflow: hidden; white-space: nowrap;
  font-size: 0.72rem; font-weight: 650; color: color-mix(in srgb, var(--d-hs) 90%, var(--d-ink)); pointer-events: none;
  background: repeating-linear-gradient(45deg, color-mix(in srgb, var(--d-hs) 14%, transparent) 0 6px, color-mix(in srgb, var(--d-hs) 4%, transparent) 6px 12px);
  border-left: 2px solid color-mix(in srgb, var(--d-hs) 60%, transparent); }
.planning__hs span { overflow: hidden; text-overflow: ellipsis; }
.planning__case { border-right: 1px solid var(--hairline, #eef0ee); cursor: pointer; transition: background 0.14s; }
.planning__case:hover { background: color-mix(in srgb, var(--color-primary) 8%, transparent); }
.planning__case--we { background: color-mix(in srgb, var(--color-primary) 3%, transparent); }
.planning__case--aujourdhui { background: color-mix(in srgb, var(--color-primary) 5%, transparent); box-shadow: inset 2px 0 0 color-mix(in srgb, var(--color-primary) 60%, transparent); }
.planning__barre { position: absolute; top: 8px; height: 34px; border-radius: 11px; padding: 0 12px 0 14px; display: flex; align-items: center;
  font-size: 0.78rem; font-weight: 650; letter-spacing: -0.005em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: pointer;
  color: #fff; background: linear-gradient(180deg, color-mix(in srgb, var(--c) 88%, #fff), color-mix(in srgb, var(--c) 82%, #000));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.25), 0 6px 14px -8px color-mix(in srgb, var(--c) 80%, transparent);
  transition: transform 0.16s, box-shadow 0.16s; }
.planning__barre::before { content: ''; position: absolute; left: 5px; top: 8px; bottom: 8px; width: 3px; border-radius: 3px; background: rgba(255, 255, 255, 0.55); }
.planning__barre:hover { transform: translateY(-1px); box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.25), 0 10px 20px -10px color-mix(in srgb, var(--c) 90%, transparent); }
.planning__barre[draggable='true'] { cursor: grab; }
</style>
