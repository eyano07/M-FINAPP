<script setup lang="ts">
import { useDisplay } from 'vuetify'
import { useEtablissementStore } from '~/stores/etablissement'
import { useSessionStore } from '~/stores/session'
import { STATUTS, MENAGE } from '~/utils/libelles'

/**
 * Tableau de bord (design 2027) : bandeau d'accueil avec l'occupation du soir, indicateurs du jour, points à traiter,
 * arrivées et départs en frise horaire, et plan vivant des unités (une tuile par unité, couleur de son état).
 */
useHead({ title: 'Tableau de bord' })

interface Ligne { id: number, reference: string, debut: string, fin: string, statut: string, encaissement: string | null, unite: string, client: string }
interface Tableau {
  unitesDisponibles: number, unitesOccupees: number, tauxOccupation: number, presents: number, unitesANettoyer: number,
  unitesHorsService: number, arriveesDuJour: Ligne[], departsDuJour: Ligne[], encaissementsEnAttente: Ligne[],
  demandesEnLigne?: Ligne[], alertesCanaux?: number, ticketsOuverts?: number, ticketsUrgents?: number, echeancesAFacturer?: number
}
interface Unite { id: number, etablissementNom: string, numero: string, typeNom: string, etage: string | null, etatMenage: string,
  horsService: boolean, motifHorsService: string | null, occupee: boolean, clientActuel: string | null, departPrevu: string | null, arriveePrevue: string | null }

const api = useApi()
const session = useSessionStore()
const etablissements = useEtablissementStore()
const { xs } = useDisplay()
const t = ref<Tableau | null>(null)
const unites = ref<Unite[]>([])
const erreur = ref('')
let minuterie: ReturnType<typeof setInterval> | null = null

async function charger() {
  try {
    const query = { etablissementId: etablissements.filtre }
    const [tableau, u] = await Promise.all([
      api<Tableau>('/tableau-de-bord', { query }),
      api<Unite[]>('/menage/unites', { query }).catch(() => [] as Unite[]),
    ])
    t.value = tableau
    unites.value = u
    erreur.value = ''
  } catch (e) {
    erreur.value = messageErreur(e, 'Tableau de bord indisponible.')
  }
}
onMounted(() => {
  charger()
  minuterie = setInterval(charger, 30_000)
})
onBeforeUnmount(() => { if (minuterie) clearInterval(minuterie) })

const salut = computed(() => {
  const h = new Date().getHours()
  const prenom = session.utilisateur?.prenom ?? ''
  return `${h < 5 || h >= 18 ? 'Bonsoir' : 'Bonjour'}${prenom ? `, ${prenom}` : ''}`
})
const aujourdhui = new Date().toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' })
const pluriel = (n: number, mot: string) => `${n} ${mot}${n > 1 ? 's' : ''}`
const resume = computed(() => {
  if (!t.value) return ''
  if (!t.value.unitesDisponibles && !unites.value.length) return 'Votre établissement n’a pas encore d’unités : commençons par le mettre en place.'
  const a = t.value.arriveesDuJour.length
  const d = t.value.departsDuJour.length
  return `${pluriel(a, 'arrivée')} et ${pluriel(d, 'départ')} aujourd’hui · ${pluriel(t.value.presents, 'client')} en séjour.`
})
const peutReserver = computed(() => session.a('RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN'))
const peutParametrer = computed(() => session.a('DIR_HEBERGEMENT', 'ADMIN'))
const vide = computed(() => !!t.value && !t.value.unitesDisponibles && !unites.value.length)

const heure = (iso: string) => new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
const initiales = (nom: string) => nom.split(/\s+/).filter(Boolean).slice(0, 2).map(m => m[0]).join('').toUpperCase() || '?'
const TEINTES = ['#2563eb', '#7c3aed', '#db2777', '#d97706', '#0d9488', '#4f46e5', '#0891b2', '#c026d3']
const teinte = (nom: string) => TEINTES[[...nom].reduce((s, c) => s + c.charCodeAt(0), 0) % TEINTES.length]

const kpis = computed(() => {
  if (!t.value) return []
  const x = t.value
  return [
    { libelle: 'Arrivées du jour', valeur: x.arriveesDuJour.length, icone: 'mdi-login-variant', c: '#2563eb',
      note: x.arriveesDuJour.length ? 'À accueillir aujourd’hui' : 'Aucune attendue', vers: '/reservations' },
    { libelle: 'Départs du jour', valeur: x.departsDuJour.length, icone: 'mdi-logout-variant', c: '#d97706',
      note: x.departsDuJour.length ? 'Folios à encaisser' : 'Aucun prévu', vers: '/reservations' },
    { libelle: 'Clients en séjour', valeur: x.presents, icone: 'mdi-account-heart-outline', c: 'var(--color-primary)',
      note: `${x.unitesOccupees} unité(s) occupée(s)`, vers: '/calendrier' },
    { libelle: 'Unités à préparer', valeur: x.unitesANettoyer, icone: 'mdi-broom', c: '#e11d48',
      note: x.unitesANettoyer ? 'Ménage en attente' : 'Tout est propre', vers: '/menage' },
  ]
})

const aTraiter = computed(() => {
  if (!t.value) return []
  const r: { icone: string, c: string, titre: string, texte: string, vers: string, action: string }[] = []
  for (const l of t.value.demandesEnLigne ?? []) {
    r.push({ icone: 'mdi-web', c: '#7c3aed', titre: `Demande en ligne · ${l.client}`, texte: `${l.reference} — à confirmer sous 48 h`, vers: `/reservations/${l.id}`, action: 'Confirmer' })
  }
  if (t.value.alertesCanaux) {
    r.push({ icone: 'mdi-calendar-alert', c: '#e11d48', titre: `${t.value.alertesCanaux} conflit(s) avec les canaux`, texte: 'Une réservation reçue tombe sur des dates déjà occupées.', vers: '/canaux', action: 'Résoudre' })
  }
  if (t.value.ticketsUrgents) {
    r.push({ icone: 'mdi-wrench-clock', c: '#e11d48', titre: `${t.value.ticketsUrgents} panne(s) urgente(s)`, texte: `${t.value.ticketsOuverts ?? 0} ticket(s) de maintenance ouverts au total`, vers: '/maintenance', action: 'Voir' })
  }
  if (t.value.echeancesAFacturer) {
    r.push({ icone: 'mdi-file-clock-outline', c: '#4338ca', titre: `${t.value.echeancesAFacturer} loyer(s) échu(s) à facturer`, texte: 'Baux des appartements : la facture part à crédit dans M-FINAPP', vers: '/baux', action: 'Facturer' })
  }
  for (const l of t.value.encaissementsEnAttente) {
    r.push({ icone: 'mdi-cash-clock', c: '#d97706', titre: `Encaissement en attente · ${l.client}`, texte: `${l.reference} — M-FINAPP était injoignable au départ`, vers: `/reservations/${l.id}`, action: 'Encaisser' })
  }
  return r
})

// ── Plan des unités ─────────────────────────────────────────────────────────────────────────────────────────
type Etat = 'occupee' | 'libre' | 'attendue' | 'A_NETTOYER' | 'EN_COURS' | 'A_INSPECTER' | 'hs'
const LEGENDE: Record<Etat, { libelle: string, c: string }> = {
  occupee: { libelle: 'Occupée', c: 'var(--color-primary)' },
  attendue: { libelle: 'Arrivée attendue', c: 'var(--res-option)' },
  libre: { libelle: 'Libre et prête', c: 'var(--menage-propre)' },
  A_NETTOYER: { libelle: 'À nettoyer', c: 'var(--menage-sale)' },
  EN_COURS: { libelle: 'Ménage en cours', c: 'var(--menage-encours)' },
  A_INSPECTER: { libelle: 'À inspecter', c: 'var(--menage-inspecter)' },
  hs: { libelle: 'Hors service', c: 'var(--d-hs)' },
}
function etat(u: Unite): Etat {
  if (u.horsService) return 'hs'
  if (u.occupee) return 'occupee'
  if (u.etatMenage !== 'PROPRE') return u.etatMenage as Etat
  if (u.arriveePrevue && new Date(u.arriveePrevue).toDateString() === new Date().toDateString()) return 'attendue'
  return 'libre'
}
function bulle(u: Unite) {
  const e = LEGENDE[etat(u)].libelle
  const detail = u.horsService ? (u.motifHorsService ?? '') : u.occupee ? `${u.clientActuel ?? ''}` : MENAGE[u.etatMenage]?.libelle ?? ''
  return `${u.numero} · ${u.typeNom} — ${e}${detail && detail !== e ? ` (${detail})` : ''}`
}
const groupes = computed(() => {
  const m = new Map<string, Unite[]>()
  for (const u of unites.value) m.set(u.etablissementNom, [...(m.get(u.etablissementNom) ?? []), u])
  return [...m.entries()].map(([nom, liste]) => ({ nom, liste }))
})
const comptes = computed(() => {
  const c: Partial<Record<Etat, number>> = {}
  for (const u of unites.value) { const e = etat(u); c[e] = (c[e] ?? 0) + 1 }
  return c
})
</script>

<template>
  <div class="tableau d-apparait">
    <!-- Bandeau d'accueil -->
    <section class="accueil">
      <div class="accueil__texte">
        <div class="accueil__surtitre"><span class="accueil__date">{{ aujourdhui }}</span><span v-if="etablissements.liste.length"> · {{ etablissements.nom }}</span></div>
        <h1 class="accueil__titre">{{ salut }}</h1>
        <p class="accueil__resume">{{ resume || ' ' }}</p>
        <div class="accueil__actions">
          <v-btn v-if="peutReserver && !vide" class="accueil__btn" variant="flat" size="large" prepend-icon="mdi-plus" to="/reservations?nouvelle=1">Nouvelle réservation</v-btn>
          <v-btn v-if="vide && peutParametrer" class="accueil__btn" variant="flat" size="large" prepend-icon="mdi-rocket-launch-outline" to="/parametrage">Mettre en place</v-btn>
          <v-btn class="accueil__btn-fantome" variant="outlined" size="large" prepend-icon="mdi-calendar-month-outline" to="/calendrier">Calendrier</v-btn>
        </div>
      </div>
      <div class="accueil__occupation">
        <AnneauOccupation :valeur="t && t.unitesDisponibles ? t.tauxOccupation : null" clair :taille="xs ? 112 : 176" :epaisseur="xs ? 11 : 14" />
        <div class="accueil__occ-texte">
          <div class="accueil__occ-titre">Occupation ce soir</div>
          <div class="accueil__occ-detail">{{ t ? `${t.unitesOccupees} / ${t.unitesDisponibles} unités` : '…' }}</div>
          <div class="accueil__puces">
            <span><i style="--c: #a7f3d0" />{{ t ? Math.max(0, t.unitesDisponibles - t.unitesOccupees) : 0 }} libres</span>
            <span><i style="--c: #fda4af" />{{ t?.unitesANettoyer ?? 0 }} à préparer</span>
            <span><i style="--c: #cbd5e1" />{{ t?.unitesHorsService ?? 0 }} hors service</span>
          </div>
        </div>
      </div>
    </section>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <!-- Mise en place (aucune unité) -->
    <section v-if="vide" class="d-carte mise-en-place">
      <div class="mise-en-place__visuel" aria-hidden="true">
        <svg viewBox="0 0 220 150" fill="none">
          <rect x="34" y="34" width="90" height="100" rx="10" class="mp-trait" />
          <rect x="124" y="64" width="62" height="70" rx="10" class="mp-trait" />
          <g class="mp-fen"><rect x="50" y="52" width="18" height="16" rx="4" /><rect x="90" y="52" width="18" height="16" rx="4" />
            <rect x="50" y="80" width="18" height="16" rx="4" /><rect x="90" y="80" width="18" height="16" rx="4" />
            <rect x="140" y="80" width="14" height="14" rx="4" /><rect x="160" y="80" width="14" height="14" rx="4" /></g>
          <rect x="70" y="108" width="18" height="26" rx="4" class="mp-porte" />
          <path d="M20 134h190" class="mp-trait" stroke-linecap="round" />
          <circle cx="182" cy="34" r="12" class="mp-soleil" />
        </svg>
      </div>
      <div class="mise-en-place__texte">
        <div class="page-eyebrow">Premiers pas</div>
        <h2 class="mise-en-place__titre">Mettez votre établissement en place</h2>
        <ol class="etapes">
          <li><span>1</span><div><strong>L’établissement</strong><small>Hôtel, motel ou appartements, heures d’arrivée et de départ.</small></div></li>
          <li><span>2</span><div><strong>Types et unités</strong><small>Chambres, unités de motel ou appartements, avec leur capacité.</small></div></li>
          <li><span>3</span><div><strong>Tarifs</strong><small>À l’heure, à la nuit, à la semaine ou au mois, saisons comprises.</small></div></li>
        </ol>
        <v-btn v-if="peutParametrer" color="primary" variant="flat" size="large" append-icon="mdi-arrow-right" to="/parametrage">Commencer</v-btn>
        <p v-else class="text-body-2 text-medium-emphasis mb-0">La direction de l’hébergement crée les établissements, les unités et les tarifs.</p>
      </div>
    </section>

    <!-- Indicateurs du jour -->
    <div v-if="t && !vide" class="kpis">
      <NuxtLink v-for="k in kpis" :key="k.libelle" :to="k.vers" class="d-carte d-carte--lien kpi">
        <div class="d-kpi">
          <div class="d-kpi__tete">
            <span class="d-kpi__libelle">{{ k.libelle }}</span>
            <span class="d-icone d-icone--sm" :style="{ '--c': k.c }"><v-icon :icon="k.icone" size="18" /></span>
          </div>
          <div class="d-kpi__valeur">{{ k.valeur }}</div>
          <div class="d-kpi__note">{{ k.note }}</div>
        </div>
      </NuxtLink>
    </div>

    <!-- À traiter -->
    <section v-if="aTraiter.length" class="d-carte a-traiter">
      <div class="d-section">
        <span class="d-icone d-icone--sm" style="--c: #d97706"><v-icon icon="mdi-lightning-bolt-outline" size="18" /></span>
        <div><div class="d-section__titre">À traiter</div><div class="d-section__sous">Ce qui attend une action de la réception</div></div>
        <v-spacer /><span class="d-compteur">{{ aTraiter.length }}</span>
      </div>
      <div class="a-traiter__liste">
        <NuxtLink v-for="(a, i) in aTraiter" :key="i" :to="a.vers" class="a-traiter__ligne">
          <span class="d-icone d-icone--sm" :style="{ '--c': a.c }"><v-icon :icon="a.icone" size="17" /></span>
          <span class="a-traiter__texte"><strong>{{ a.titre }}</strong><small>{{ a.texte }}</small></span>
          <span class="a-traiter__action">{{ a.action }}<v-icon icon="mdi-arrow-right" size="16" /></span>
        </NuxtLink>
      </div>
    </section>

    <!-- Arrivées et départs -->
    <div v-if="t && !vide" class="flux">
      <section v-for="bloc in [
        { titre: 'Arrivées du jour', sous: 'Clients attendus aujourd’hui', icone: 'mdi-login-variant', c: '#2563eb', lignes: t.arriveesDuJour, cle: 'debut', vide: 'Aucune arrivée prévue aujourd’hui.' },
        { titre: 'Départs du jour', sous: 'Folios à clôturer et encaisser', icone: 'mdi-logout-variant', c: '#d97706', lignes: t.departsDuJour, cle: 'fin', vide: 'Aucun départ prévu aujourd’hui.' },
      ]" :key="bloc.titre" class="d-carte">
        <div class="d-section">
          <span class="d-icone d-icone--sm" :style="{ '--c': bloc.c }"><v-icon :icon="bloc.icone" size="18" /></span>
          <div><div class="d-section__titre">{{ bloc.titre }}</div><div class="d-section__sous">{{ bloc.sous }}</div></div>
          <v-spacer /><span class="d-compteur">{{ bloc.lignes.length }}</span>
        </div>
        <div v-if="bloc.lignes.length" class="frise">
          <NuxtLink v-for="l in bloc.lignes" :key="l.id" :to="`/reservations/${l.id}`" class="frise__ligne">
            <span class="frise__heure">{{ heure((l as any)[bloc.cle]) }}</span>
            <span class="frise__avatar" :style="{ '--c': teinte(l.client) }">{{ initiales(l.client) }}</span>
            <span class="frise__texte"><strong>{{ l.client }}</strong><small>{{ l.reference }}</small></span>
            <span class="frise__unite">{{ l.unite }}</span>
            <span class="pastille" :style="{ '--c': STATUTS[l.statut]?.couleur }">{{ STATUTS[l.statut]?.libelle }}</span>
          </NuxtLink>
        </div>
        <div v-else class="d-vide">
          <div class="d-vide__icone"><v-icon :icon="bloc.icone" size="28" /></div>
          <div class="d-vide__titre">{{ bloc.vide }}</div>
          <div class="d-vide__texte">La liste se met à jour toute seule, toutes les 30 secondes.</div>
        </div>
      </section>
    </div>

    <!-- Plan des unités -->
    <section v-if="unites.length" class="d-carte plan">
      <div class="d-section">
        <span class="d-icone d-icone--sm"><v-icon icon="mdi-floor-plan" size="18" /></span>
        <div><div class="d-section__titre">Plan des unités</div><div class="d-section__sous">État de chaque unité, en temps réel</div></div>
        <v-spacer /><span class="d-direct">En direct</span>
      </div>
      <div class="plan__legende">
        <span v-for="(l, cle) in LEGENDE" v-show="comptes[cle as Etat]" :key="cle" class="plan__cle" :style="{ '--c': l.c }">
          <i :class="`plan__echantillon plan__echantillon--${cle}`" />{{ l.libelle }}<b>{{ comptes[cle as Etat] }}</b>
        </span>
      </div>
      <div v-for="g in groupes" :key="g.nom" class="plan__groupe">
        <div v-if="groupes.length > 1" class="plan__nom">{{ g.nom }}</div>
        <div class="plan__grille">
          <NuxtLink v-for="u in g.liste" :key="u.id" to="/menage" class="plan__tuile" :class="`plan__tuile--${etat(u)}`"
            :style="{ '--c': LEGENDE[etat(u)].c }" :title="bulle(u)">
            <span class="plan__numero">{{ u.numero }}</span>
            <span class="plan__type">{{ u.typeNom }}</span>
          </NuxtLink>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.tableau { display: flex; flex-direction: column; gap: 20px; }

/* ── Bandeau d'accueil ── */
.accueil {
  position: relative; overflow: hidden; isolation: isolate; display: flex; align-items: center; justify-content: space-between; gap: 28px;
  padding: clamp(24px, 4vw, 40px); border-radius: var(--d-r-xl); color: #fff; background: var(--d-grad-hero);
  box-shadow: 0 30px 60px -34px color-mix(in srgb, var(--color-primary) 85%, #000);
}
.accueil::before {
  content: ''; position: absolute; inset: 0; z-index: -1; opacity: 0.55;
  background-image: linear-gradient(rgba(255, 255, 255, 0.07) 1px, transparent 1px), linear-gradient(90deg, rgba(255, 255, 255, 0.07) 1px, transparent 1px);
  background-size: 34px 34px; mask-image: radial-gradient(ellipse 70% 90% at 78% 30%, #000 10%, transparent 70%);
}
.accueil::after {
  content: ''; position: absolute; z-index: -1; width: 420px; height: 420px; right: -120px; bottom: -220px; border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.16), transparent 65%);
}
.accueil__texte { min-width: 0; max-width: 620px; }
.accueil__surtitre { font-size: 0.82rem; font-weight: 600; letter-spacing: 0.02em; opacity: 0.78; }
.accueil__date { text-transform: capitalize; }
.accueil__titre { font-size: clamp(2rem, 4.4vw, 3.1rem); font-weight: 780; letter-spacing: -0.045em; line-height: 1.02; margin: 10px 0 12px; }
.accueil__resume { font-size: 1.02rem; opacity: 0.86; margin: 0 0 24px; line-height: 1.5; min-height: 1.5em; }
.accueil__actions { display: flex; flex-wrap: wrap; gap: 10px; }
.accueil__btn { background: #fff !important; color: color-mix(in srgb, var(--color-primary) 75%, #000) !important; box-shadow: 0 10px 24px -12px rgba(0, 0, 0, 0.5) !important; }
.accueil__btn-fantome { color: #fff !important; border-color: rgba(255, 255, 255, 0.35) !important; background: rgba(255, 255, 255, 0.06) !important; backdrop-filter: blur(6px); }
.accueil__occupation {
  display: flex; align-items: center; gap: 22px; padding: 18px 26px 18px 18px; border-radius: 24px; flex: none;
  background: rgba(255, 255, 255, 0.08); border: 1px solid rgba(255, 255, 255, 0.14); backdrop-filter: blur(10px);
}
.accueil__occ-titre { font-weight: 700; font-size: 1rem; letter-spacing: -0.01em; }
.accueil__occ-detail { font-size: 0.85rem; opacity: 0.75; margin-top: 2px; font-variant-numeric: tabular-nums; }
.accueil__puces { display: grid; gap: 6px; margin-top: 14px; font-size: 0.8rem; opacity: 0.92; font-variant-numeric: tabular-nums; }
.accueil__puces span { display: inline-flex; align-items: center; gap: 8px; }
.accueil__puces i { width: 8px; height: 8px; border-radius: 50%; background: var(--c); }

/* ── Mise en place ── */
.mise-en-place { display: grid; grid-template-columns: 280px 1fr; gap: 28px; align-items: center; padding: 28px 32px; }
.mise-en-place__visuel {
  border-radius: 22px; padding: 18px; background: radial-gradient(circle at 70% 20%, color-mix(in srgb, var(--color-primary) 14%, transparent), transparent 70%), var(--d-surface-2);
  border: 1px solid var(--d-line);
}
.mise-en-place__visuel svg { width: 100%; height: auto; display: block; }
.mp-trait { stroke: color-mix(in srgb, var(--color-primary) 70%, var(--d-ink)); stroke-width: 2.5; }
.mp-fen rect { fill: color-mix(in srgb, var(--color-primary) 22%, transparent); }
.mp-porte { fill: var(--color-primary); }
.mp-soleil { fill: color-mix(in srgb, #f59e0b 70%, transparent); }
.mise-en-place__titre { font-size: 1.45rem; font-weight: 760; letter-spacing: -0.03em; margin: 0 0 16px; }
.etapes { list-style: none; padding: 0; margin: 0 0 22px; display: grid; gap: 12px; }
.etapes li { display: flex; gap: 14px; align-items: flex-start; }
.etapes li > span {
  width: 28px; height: 28px; border-radius: 9px; flex: none; display: grid; place-items: center; font-weight: 800; font-size: 0.82rem;
  color: #fff; background: var(--d-grad);
}
.etapes strong { display: block; font-size: 0.95rem; }
.etapes small { display: block; color: var(--d-ink-soft); font-size: 0.84rem; }

/* ── Indicateurs ── */
.kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }
.kpi { text-decoration: none; color: inherit; display: block; }

/* ── À traiter ── */
.a-traiter__liste { padding: 6px 10px 12px; display: grid; gap: 2px; }
.a-traiter__ligne {
  display: flex; align-items: center; gap: 14px; padding: 10px; border-radius: 14px; text-decoration: none; color: inherit; transition: background 0.16s;
}
.a-traiter__ligne:hover { background: var(--d-surface-2); }
.a-traiter__texte { flex: 1; min-width: 0; }
.a-traiter__texte strong { display: block; font-size: 0.9rem; }
.a-traiter__texte small { display: block; color: var(--d-ink-soft); font-size: 0.8rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.a-traiter__action { display: inline-flex; align-items: center; gap: 4px; font-size: 0.82rem; font-weight: 700; color: var(--color-primary); white-space: nowrap; }

/* ── Arrivées et départs ── */
.flux { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.frise { padding: 8px 12px 14px; display: grid; gap: 2px; }
.frise__ligne {
  position: relative; display: flex; align-items: center; gap: 12px; padding: 10px; border-radius: 14px; text-decoration: none; color: inherit;
  transition: background 0.16s;
}
.frise__ligne:hover { background: var(--d-surface-2); }
.frise__heure { width: 50px; flex: none; font-weight: 750; font-size: 0.92rem; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
.frise__avatar {
  width: 38px; height: 38px; border-radius: 50%; flex: none; display: grid; place-items: center; font-size: 0.78rem; font-weight: 800;
  color: var(--c); background: color-mix(in srgb, var(--c) 12%, var(--d-surface)); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--c) 20%, transparent);
}
.frise__texte { flex: 1; min-width: 0; }
.frise__texte strong { display: block; font-size: 0.9rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.frise__texte small { display: block; font-size: 0.76rem; color: var(--d-ink-faint); }
.frise__unite {
  min-width: 44px; padding: 4px 10px; border-radius: 10px; text-align: center; font-weight: 750; font-size: 0.84rem; font-variant-numeric: tabular-nums;
  background: var(--d-surface-3); color: var(--d-ink-2);
}

/* ── Plan des unités ── */
.plan { padding-bottom: 20px; }
.plan__legende { display: flex; flex-wrap: wrap; gap: 8px 18px; padding: 8px 20px 16px; }
.plan__cle { display: inline-flex; align-items: center; gap: 8px; font-size: 0.8rem; color: var(--d-ink-soft); }
.plan__cle b { color: var(--d-ink); font-variant-numeric: tabular-nums; }
.plan__echantillon { width: 14px; height: 14px; border-radius: 5px; background: color-mix(in srgb, var(--c) 18%, var(--d-surface)); box-shadow: inset 0 0 0 1.5px var(--c); }
.plan__echantillon--occupee { background: var(--d-grad); box-shadow: none; }
.plan__echantillon--hs { background: repeating-linear-gradient(45deg, color-mix(in srgb, var(--c) 30%, transparent) 0 3px, transparent 3px 6px); }
.plan__groupe { padding: 0 20px; }
.plan__groupe + .plan__groupe { margin-top: 16px; }
.plan__nom { font-size: 0.72rem; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: var(--d-ink-faint); margin-bottom: 8px; }
.plan__grille { display: grid; grid-template-columns: repeat(auto-fill, minmax(78px, 1fr)); gap: 8px; }
.plan__tuile {
  position: relative; display: flex; flex-direction: column; justify-content: space-between; aspect-ratio: 1 / 0.86; padding: 9px 10px;
  border-radius: 14px; text-decoration: none; color: var(--d-ink); overflow: hidden;
  background: color-mix(in srgb, var(--c) 9%, var(--d-surface)); box-shadow: inset 0 0 0 1.5px color-mix(in srgb, var(--c) 45%, transparent);
  transition: transform 0.18s, box-shadow 0.18s;
}
.plan__tuile:hover { transform: translateY(-2px) scale(1.02); box-shadow: inset 0 0 0 1.5px var(--c), var(--d-shadow-2); }
.plan__tuile--occupee { color: #fff; background: var(--d-grad); box-shadow: 0 8px 18px -12px color-mix(in srgb, var(--color-primary) 90%, transparent); }
.plan__tuile--occupee .plan__type { color: rgba(255, 255, 255, 0.78); }
.plan__tuile--hs { background: repeating-linear-gradient(45deg, color-mix(in srgb, var(--c) 14%, var(--d-surface)) 0 6px, var(--d-surface) 6px 12px); }
.plan__tuile--attendue::after {
  content: ''; position: absolute; top: 8px; right: 8px; width: 7px; height: 7px; border-radius: 50%; background: var(--c);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--c) 25%, transparent);
}
.plan__numero { font-weight: 780; font-size: 1.05rem; letter-spacing: -0.03em; font-variant-numeric: tabular-nums; }
.plan__type { font-size: 0.66rem; color: var(--d-ink-faint); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

@media (max-width: 1279px) {
  .kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 959px) {
  .accueil { flex-direction: column; align-items: stretch; }
  .accueil__occupation { justify-content: flex-start; }
  .flux { grid-template-columns: 1fr; }
  .mise-en-place { grid-template-columns: 1fr; padding: 22px; }
  .mise-en-place__visuel { max-width: 300px; }
}
@media (max-width: 599px) {
  .kpis { gap: 10px; }
  .accueil__occupation { gap: 16px; padding: 14px; }
  .accueil__puces { margin-top: 8px; gap: 4px; }
  .accueil__resume { margin-bottom: 18px; }
  .frise__heure { width: 42px; }
  .frise .pastille { display: none; }
}
</style>
