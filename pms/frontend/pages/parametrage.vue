<script setup lang="ts">
import { useSessionStore } from '~/stores/session'
import { DUREES, TYPES_ETABLISSEMENT, usd } from '~/utils/libelles'

/** Établissements, types d'unité, unités et tarifs (direction de l'hébergement et administrateur). */
useHead({ title: 'Établissements et tarifs' })

interface Etab { id: number, nom: string, type: string, adresse: string | null, telephone: string | null, heureArrivee: string, heureDepart: string, actif: boolean, unites: number }
interface TypeU { id: number, etablissementId: number, nom: string, capacite: number, description: string | null, actif: boolean }
interface Unite { id: number, etablissementId: number, etablissementNom: string, typeId: number, typeNom: string, numero: string, etage: string | null,
  capacite: number | null, equipements: string | null, horsService: boolean, motifHorsService: string | null, notes: string | null, ordre: number, actif: boolean }
interface Tarif { id: number, typeId: number, typeNom: string, etablissementId: number, uniteDuree: string, prix: number, libelle: string | null,
  debutSaison: string | null, finSaison: string | null, actif: boolean, dureeHeures: number | null, plageDebut: string | null, plageFin: string | null }
interface TarifCompteur { etablissementId: number, type: 'EAU' | 'ELECTRICITE', prixUnite: number }

const api = useApi()
const dialogues = useDialogues()
// Écriture : direction de l'hébergement et administrateur (le serveur refuse les autres) ; les autres consultent.
const peut = computed(() => useSessionStore().a('DIR_HEBERGEMENT', 'ADMIN'))
const onglet = ref('etablissements')
const etabs = ref<Etab[]>([])
const types = ref<TypeU[]>([])
const unites = ref<Unite[]>([])
const tarifs = ref<Tarif[]>([])
const erreur = ref('')
const succes = ref('')

async function charger() {
  try {
    const [e, t, u, ta] = await Promise.all([api<Etab[]>('/etablissements'), api<TypeU[]>('/types-unite'), api<Unite[]>('/unites'), api<Tarif[]>('/tarifs')])
    etabs.value = e; types.value = t; unites.value = u; tarifs.value = ta
  } catch (err) {
    erreur.value = messageErreur(err)
  }
}
onMounted(charger)

const nomEtab = (id: number) => etabs.value.find(e => e.id === id)?.nom ?? ''
const optionsTypes = computed(() => types.value.map(t => ({ value: t.id, title: `${t.nom} — ${nomEtab(t.etablissementId)}` })))
const heure = (h: string) => (h ?? '').slice(0, 5)

async function enregistrer(chemin: string, idEl: number | null, corps: unknown, fermer: () => void) {
  erreur.value = ''
  try {
    await api(idEl ? `${chemin}/${idEl}` : chemin, { method: idEl ? 'PUT' : 'POST', body: corps })
    fermer()
    succes.value = 'Enregistré.'
    await charger()
  } catch (e) {
    erreur.value = messageErreur(e)
  }
}

// Établissement
const fe = reactive({ ouvert: false, id: null as number | null, nom: '', type: 'HOTEL', adresse: '', telephone: '', heureArrivee: '14:00', heureDepart: '12:00', actif: true })
function ouvrirEtab(e?: Etab) {
  Object.assign(fe, e ? { ouvert: true, id: e.id, nom: e.nom, type: e.type, adresse: e.adresse ?? '', telephone: e.telephone ?? '', heureArrivee: heure(e.heureArrivee), heureDepart: heure(e.heureDepart), actif: e.actif }
    : { ouvert: true, id: null, nom: '', type: 'HOTEL', adresse: '', telephone: '', heureArrivee: '14:00', heureDepart: '12:00', actif: true })
}
const sauverEtab = () => enregistrer('/etablissements', fe.id, { nom: fe.nom, type: fe.type, adresse: fe.adresse || null, telephone: fe.telephone || null,
  heureArrivee: fe.heureArrivee, heureDepart: fe.heureDepart, actif: fe.actif }, () => { fe.ouvert = false })

// Type d'unité
const ft = reactive({ ouvert: false, id: null as number | null, etablissementId: null as number | null, nom: '', capacite: 2, description: '', actif: true })
function ouvrirType(t?: TypeU) {
  Object.assign(ft, t ? { ouvert: true, id: t.id, etablissementId: t.etablissementId, nom: t.nom, capacite: t.capacite, description: t.description ?? '', actif: t.actif }
    : { ouvert: true, id: null, etablissementId: etabs.value[0]?.id ?? null, nom: '', capacite: 2, description: '', actif: true })
}
const sauverType = () => enregistrer('/types-unite', ft.id, { etablissementId: ft.etablissementId, nom: ft.nom, capacite: ft.capacite,
  description: ft.description || null, actif: ft.actif }, () => { ft.ouvert = false })

// Unité
const fu = reactive({ ouvert: false, id: null as number | null, typeId: null as number | null, numero: '', etage: '', capacite: null as number | null,
  equipements: '', notes: '', ordre: 0, actif: true, serie: false, jusqua: '' })
function ouvrirUnite(u?: Unite) {
  Object.assign(fu, u ? { ouvert: true, id: u.id, typeId: u.typeId, numero: u.numero, etage: u.etage ?? '', capacite: u.capacite, equipements: u.equipements ?? '',
    notes: u.notes ?? '', ordre: u.ordre, actif: u.actif, serie: false, jusqua: '' }
    : { ouvert: true, id: null, typeId: types.value[0]?.id ?? null, numero: '', etage: '', capacite: null, equipements: '', notes: '', ordre: 0, actif: true, serie: false, jusqua: '' })
}
async function sauverUnite() {
  const base = { typeId: fu.typeId, etage: fu.etage || null, capacite: fu.capacite || null, equipements: fu.equipements || null, notes: fu.notes || null, ordre: fu.ordre, actif: fu.actif }
  // Série (création seulement) : de 101 à 110, une unité par numéro.
  if (!fu.id && fu.serie && /^\d+$/.test(fu.numero) && /^\d+$/.test(fu.jusqua) && Number(fu.jusqua) >= Number(fu.numero)) {
    erreur.value = ''
    try {
      for (let n = Number(fu.numero); n <= Number(fu.jusqua); n++) {
        await api('/unites', { method: 'POST', body: { ...base, numero: String(n), ordre: n } })
      }
      fu.ouvert = false
      succes.value = `Unités ${fu.numero} à ${fu.jusqua} créées.`
      await charger()
    } catch (e) {
      erreur.value = messageErreur(e)
      await charger()
    }
    return
  }
  await enregistrer('/unites', fu.id, { ...base, numero: fu.numero }, () => { fu.ouvert = false })
}
async function basculerHorsService(u: Unite) {
  let motif: string | null = null
  if (!u.horsService) {
    motif = await dialogues.saisir({ titre: `Mettre l'unité ${u.numero} hors service ?`, libelle: 'Motif (réparation, travaux…)', nature: 'attention', bouton: 'Mettre hors service' })
    if (motif === null) return
  }
  try {
    await api(`/unites/${u.id}/hors-service`, { method: 'PUT', body: { horsService: !u.horsService, motif } })
    await charger()
  } catch (e) {
    erreur.value = messageErreur(e)
  }
}

// Tarif
const fta = reactive({ ouvert: false, id: null as number | null, typeId: null as number | null, uniteDuree: 'NUIT', prix: null as number | null,
  libelle: '', debutSaison: '', finSaison: '', actif: true, dureeHeures: 3 as number | null, plageDebut: '', plageFin: '' })
function ouvrirTarif(t?: Tarif) {
  Object.assign(fta, t ? { ouvert: true, id: t.id, typeId: t.typeId, uniteDuree: t.uniteDuree, prix: Number(t.prix), libelle: t.libelle ?? '',
    debutSaison: t.debutSaison ?? '', finSaison: t.finSaison ?? '', actif: t.actif, dureeHeures: t.dureeHeures ?? 3,
    plageDebut: t.plageDebut?.slice(0, 5) ?? '', plageFin: t.plageFin?.slice(0, 5) ?? '' }
    : { ouvert: true, id: null, typeId: types.value[0]?.id ?? null, uniteDuree: 'NUIT', prix: null, libelle: '', debutSaison: '', finSaison: '', actif: true,
      dureeHeures: 3, plageDebut: '', plageFin: '' })
}
const creneau = computed(() => fta.uniteDuree === 'CRENEAU')
const sauverTarif = () => enregistrer('/tarifs', fta.id, { typeId: fta.typeId, uniteDuree: fta.uniteDuree, prix: fta.prix, libelle: fta.libelle || null,
  debutSaison: fta.debutSaison || null, finSaison: fta.finSaison || null, actif: fta.actif,
  dureeHeures: creneau.value ? fta.dureeHeures : null, plageDebut: creneau.value && fta.plageDebut ? fta.plageDebut : null,
  plageFin: creneau.value && fta.plageFin ? fta.plageFin : null }, () => { fta.ouvert = false })

// Compteurs (appartements) : prix du m³ d'eau et du kWh refacturés aux locataires.
const tarifsCompteurs = ref<TarifCompteur[]>([])
async function chargerCompteurs() {
  try { tarifsCompteurs.value = await api<TarifCompteur[]>('/compteurs/tarifs') } catch { tarifsCompteurs.value = [] }
}
onMounted(chargerCompteurs)
const prixCompteur = (etab: number, type: string) => tarifsCompteurs.value.find(t => t.etablissementId === etab && t.type === type)?.prixUnite ?? null
async function sauverCompteur(etab: number, type: string, valeur: string) {
  const prix = Number(String(valeur).replace(',', '.'))
  if (Number.isNaN(prix) || prix < 0) return
  try {
    await api('/compteurs/tarifs', { method: 'PUT', body: { etablissementId: etab, type, prixUnite: prix } })
    await chargerCompteurs()
  } catch (e) { erreur.value = messageErreur(e) }
}
const libDuree = (d: string) => DUREES.find(x => x.value === d)?.title ?? d
const libType = (t: string) => TYPES_ETABLISSEMENT.find(x => x.value === t)?.title ?? t
</script>

<template>
  <div class="d-apparait">
    <div class="page-head">
      <div>
        <div class="page-eyebrow">Gestion</div>
        <h1 class="page-title">Établissements et tarifs</h1>
        <p class="page-sub">Hôtels, motels et résidences ; types d'unité, unités et prix</p>
      </div>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-4" @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" closable class="mb-4" @click:close="succes = ''">{{ succes }}</v-alert>

    <v-tabs v-model="onglet" color="primary" class="mb-4">
      <v-tab value="etablissements">Établissements</v-tab>
      <v-tab value="unites">Types et unités</v-tab>
      <v-tab value="tarifs">Tarifs</v-tab>
      <v-tab value="compteurs">Compteurs</v-tab>
    </v-tabs>

    <v-window v-model="onglet">
      <v-window-item value="etablissements">
        <div v-if="peut" class="d-flex justify-end mb-3"><v-btn color="primary" prepend-icon="mdi-plus" @click="ouvrirEtab()">Établissement</v-btn></div>
        <v-row>
          <v-col v-for="e in etabs" :key="e.id" cols="12" md="6" lg="4">
            <v-card class="classroom-card pa-5" @click="peut && ouvrirEtab(e)">
              <div class="d-flex align-center"><strong class="text-h6">{{ e.nom }}</strong><v-spacer /><v-chip size="small" variant="tonal">{{ libType(e.type) }}</v-chip></div>
              <div class="text-body-2 text-medium-emphasis">{{ e.adresse || 'Adresse non renseignée' }}</div>
              <div class="text-body-2 mt-2">{{ e.unites }} unité(s) · arrivée {{ heure(e.heureArrivee) }} · départ {{ heure(e.heureDepart) }}</div>
              <v-chip v-if="!e.actif" size="x-small" color="error" variant="tonal" class="mt-2">Inactif</v-chip>
            </v-card>
          </v-col>
          <v-col v-if="!etabs.length" cols="12"><v-alert type="info" variant="tonal">Commencez par créer votre hôtel, motel ou résidence d'appartements.</v-alert></v-col>
        </v-row>
      </v-window-item>

      <v-window-item value="unites">
        <div v-if="peut" class="d-flex flex-wrap justify-end ga-2 mb-3">
          <v-btn variant="tonal" prepend-icon="mdi-plus" :disabled="!etabs.length" @click="ouvrirType()">Type d'unité</v-btn>
          <v-btn color="primary" prepend-icon="mdi-plus" :disabled="!types.length" @click="ouvrirUnite()">Unité</v-btn>
        </div>
        <div class="d-flex flex-wrap ga-2 mb-4">
          <v-chip v-for="t in types" :key="t.id" :color="t.actif ? 'primary' : 'grey'" variant="tonal" prepend-icon="mdi-pencil" @click="peut && ouvrirType(t)">
            {{ t.nom }} · {{ t.capacite }} pers. · {{ nomEtab(t.etablissementId) }}
          </v-chip>
        </div>
        <v-card class="classroom-card">
          <v-data-table :items="unites" items-per-page="50" density="comfortable" no-data-text="Aucune unité."
            :headers="[{ title: 'Unité', key: 'numero' }, { title: 'Type', key: 'typeNom' }, { title: 'Établissement', key: 'etablissementNom' },
                       { title: 'Étage', key: 'etage' }, { title: 'État', key: 'horsService' }, { title: '', key: 'actions', sortable: false, align: 'end' }]">
            <template #[`item.numero`]="{ item }"><strong>{{ item.numero }}</strong><v-chip v-if="!item.actif" size="x-small" class="ml-1">inactive</v-chip></template>
            <template #[`item.horsService`]="{ item }">
              <v-chip v-if="item.horsService" size="small" color="error" variant="tonal" prepend-icon="mdi-wrench-outline">Hors service</v-chip>
              <v-chip v-else size="small" color="success" variant="tonal">En service</v-chip>
            </template>
            <template #[`item.actions`]="{ item }">
              <template v-if="peut">
              <v-btn size="small" variant="text" :prepend-icon="item.horsService ? 'mdi-check' : 'mdi-wrench-outline'" @click="basculerHorsService(item)">
                {{ item.horsService ? 'Remettre en service' : 'Hors service' }}
              </v-btn>
              <v-btn size="small" variant="text" icon="mdi-pencil" aria-label="Modifier" @click="ouvrirUnite(item)" />
              </template>
            </template>
          </v-data-table>
        </v-card>
      </v-window-item>

      <v-window-item value="tarifs">
        <div v-if="peut" class="d-flex justify-end mb-3"><v-btn color="primary" prepend-icon="mdi-plus" :disabled="!types.length" @click="ouvrirTarif()">Tarif</v-btn></div>
        <v-card class="classroom-card">
          <v-data-table :items="tarifs" items-per-page="50" density="comfortable" no-data-text="Aucun tarif : la réception saisira le prix à chaque réservation."
            :headers="[{ title: 'Type d\'unité', key: 'typeNom' }, { title: 'Durée', key: 'uniteDuree' }, { title: 'Prix HT', key: 'prix', align: 'end' },
                       { title: 'Libellé', key: 'libelle' }, { title: 'Saison', key: 'debutSaison' }, { title: '', key: 'actions', sortable: false, align: 'end' }]">
            <template #[`item.typeNom`]="{ item }">{{ item.typeNom }} <span class="text-caption text-medium-emphasis">{{ nomEtab(item.etablissementId) }}</span></template>
            <template #[`item.uniteDuree`]="{ item }">{{ libDuree(item.uniteDuree) }}<span v-if="item.uniteDuree === 'CRENEAU'" class="text-caption text-medium-emphasis">
              · {{ item.dureeHeures }} h<template v-if="item.plageDebut"> · {{ item.plageDebut.slice(0, 5) }}–{{ item.plageFin?.slice(0, 5) }}</template></span></template>
            <template #[`item.prix`]="{ item }"><strong>{{ usd(item.prix) }}</strong></template>
            <template #[`item.debutSaison`]="{ item }">{{ item.debutSaison ? `${item.debutSaison} → ${item.finSaison ?? '…'}` : 'Toute l\'année' }}</template>
            <template #[`item.actions`]="{ item }"><v-btn v-if="peut" size="small" variant="text" icon="mdi-pencil" aria-label="Modifier" @click="ouvrirTarif(item)" /></template>
          </v-data-table>
        </v-card>
      </v-window-item>

      <v-window-item value="compteurs">
        <v-alert type="info" variant="tonal" density="compact" class="mb-4">
          Prix refacturés aux locataires des baux : la consommation entre deux relevés (eau en m³, électricité en kWh) s’ajoute aux charges de l’échéance.
        </v-alert>
        <div class="compteurs">
          <v-card v-for="e in etabs" :key="e.id" class="classroom-card pa-5">
            <div class="font-weight-bold mb-3">{{ e.nom }}</div>
            <v-row dense>
              <v-col cols="6"><v-text-field :model-value="prixCompteur(e.id, 'EAU') ?? ''" type="number" min="0" step="0.01" label="Eau (USD / m³)" prepend-inner-icon="mdi-water-outline"
                :readonly="!peut" @change="(ev: Event) => sauverCompteur(e.id, 'EAU', (ev.target as HTMLInputElement).value)" /></v-col>
              <v-col cols="6"><v-text-field :model-value="prixCompteur(e.id, 'ELECTRICITE') ?? ''" type="number" min="0" step="0.01" label="Électricité (USD / kWh)" prepend-inner-icon="mdi-flash-outline"
                :readonly="!peut" @change="(ev: Event) => sauverCompteur(e.id, 'ELECTRICITE', (ev.target as HTMLInputElement).value)" /></v-col>
            </v-row>
          </v-card>
        </div>
      </v-window-item>
    </v-window>

    <v-dialog v-model="fe.ouvert" max-width="560">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">{{ fe.id ? 'Modifier' : 'Nouvel' }} établissement</v-card-title>
        <v-card-text>
          <v-text-field v-model="fe.nom" label="Nom *" />
          <v-select v-model="fe.type" :items="TYPES_ETABLISSEMENT" label="Type" />
          <v-text-field v-model="fe.adresse" label="Adresse" />
          <v-text-field v-model="fe.telephone" label="Téléphone" />
          <v-row dense>
            <v-col cols="6"><v-text-field v-model="fe.heureArrivee" type="time" label="Heure d'arrivée" /></v-col>
            <v-col cols="6"><v-text-field v-model="fe.heureDepart" type="time" label="Heure de départ" /></v-col>
          </v-row>
          <v-switch v-model="fe.actif" label="Actif" color="primary" hide-details inset />
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="fe.ouvert = false">Annuler</v-btn><v-btn color="primary" variant="flat" :disabled="!fe.nom" @click="sauverEtab">Enregistrer</v-btn></v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="ft.ouvert" max-width="520">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">Type d'unité</v-card-title>
        <v-card-text>
          <v-select v-model="ft.etablissementId" :items="etabs" item-title="nom" item-value="id" label="Établissement" :disabled="!!ft.id" />
          <v-text-field v-model="ft.nom" label="Nom (ex. Chambre double, Studio, Suite) *" />
          <v-text-field v-model.number="ft.capacite" type="number" min="1" label="Capacité (personnes)" />
          <v-textarea v-model="ft.description" label="Description" rows="2" auto-grow />
          <v-switch v-model="ft.actif" label="Actif" color="primary" hide-details inset />
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="ft.ouvert = false">Annuler</v-btn><v-btn color="primary" variant="flat" :disabled="!ft.nom || !ft.etablissementId" @click="sauverType">Enregistrer</v-btn></v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="fu.ouvert" max-width="560">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">{{ fu.id ? 'Modifier l\'unité' : 'Nouvelle unité' }}</v-card-title>
        <v-card-text>
          <v-select v-model="fu.typeId" :items="optionsTypes" label="Type d'unité" />
          <v-row dense>
            <v-col :cols="fu.serie ? 6 : 12"><v-text-field v-model="fu.numero" :label="fu.serie ? 'De (numéro)' : 'Numéro / nom *'" /></v-col>
            <v-col v-if="fu.serie" cols="6"><v-text-field v-model="fu.jusqua" label="À (numéro)" /></v-col>
          </v-row>
          <v-checkbox v-if="!fu.id" v-model="fu.serie" label="Créer une série de numéros (ex. 101 à 110)" density="compact" hide-details class="mb-2" />
          <v-row dense>
            <v-col cols="6"><v-text-field v-model="fu.etage" label="Étage" /></v-col>
            <v-col cols="6"><v-text-field v-model.number="fu.capacite" type="number" min="1" label="Capacité (si différente)" /></v-col>
          </v-row>
          <v-text-field v-model="fu.equipements" label="Équipements (climatisation, TV, cuisine…)" />
          <v-text-field v-model="fu.notes" label="Notes" />
          <v-switch v-model="fu.actif" label="Active" color="primary" hide-details inset />
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="fu.ouvert = false">Annuler</v-btn><v-btn color="primary" variant="flat" :disabled="!fu.numero || !fu.typeId" @click="sauverUnite">Enregistrer</v-btn></v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="fta.ouvert" max-width="520">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">Tarif</v-card-title>
        <v-card-text>
          <v-select v-model="fta.typeId" :items="optionsTypes" label="Type d'unité" />
          <v-row dense>
            <v-col cols="6"><v-select v-model="fta.uniteDuree" :items="DUREES" label="Durée" /></v-col>
            <v-col cols="6"><v-text-field v-model.number="fta.prix" type="number" min="0" label="Prix (USD HT) *" /></v-col>
          </v-row>
          <v-text-field v-model="fta.libelle" :label="creneau ? 'Nom du forfait * (ex. Sieste 3 h, Nuit motel)' : 'Libellé (ex. Haute saison, Week-end)'" />
          <v-row v-if="creneau" dense>
            <v-col cols="4"><v-text-field v-model.number="fta.dureeHeures" type="number" min="1" max="24" label="Durée (heures) *" /></v-col>
            <v-col cols="4"><v-text-field v-model="fta.plageDebut" type="time" label="Arrivée de" hint="facultatif" persistent-hint /></v-col>
            <v-col cols="4"><v-text-field v-model="fta.plageFin" type="time" label="à" /></v-col>
          </v-row>
          <p v-if="creneau" class="text-caption text-medium-emphasis">Le prix est celui du forfait entier. La plage d’arrivée (qui peut passer minuit) fait proposer ce forfait en premier.</p>
          <v-row dense>
            <v-col cols="6"><v-text-field v-model="fta.debutSaison" type="date" label="Saison : du" /></v-col>
            <v-col cols="6"><v-text-field v-model="fta.finSaison" type="date" label="au" /></v-col>
          </v-row>
          <v-switch v-model="fta.actif" label="Actif" color="primary" hide-details inset />
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="fta.ouvert = false">Annuler</v-btn><v-btn color="primary" variant="flat" :disabled="fta.prix == null || !fta.typeId" @click="sauverTarif">Enregistrer</v-btn></v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.compteurs { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
</style>
