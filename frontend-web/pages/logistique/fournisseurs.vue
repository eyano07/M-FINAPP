<script setup lang="ts">
// Répertoire des fournisseurs (V141). Hors grille des modules : le serveur (FournisseurService) ouvre la lecture
// aux acheteurs (logistique, restaurant, cuisine, bar, patrimoine), à la comptabilité et à la direction, et
// l'écriture aux mêmes sans le DA ni le DG. Même liste ici et dans le menu (NavigationDrawer). Pas le caissier.
definePageMeta({
  roles: ['LOGISTIQUE', 'RESP_RESTAURANT', 'RESP_CUISINE', 'RESP_BAR', 'GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN'],
})

interface Fournisseur {
  id: number
  code: string
  nom: string
  contact?: string | null
  telephone?: string | null
  email?: string | null
  adresse?: string | null
  nif?: string | null
  rccm?: string | null
  compteComptable: string
  actif: boolean
  /** Lots de stock et immobilisations à son nom : une fiche qui en a s'archive au lieu de se supprimer. */
  nombreAchats: number
}

const api = useApi()
const auth = useAuthStore()
const { confirmer } = useDialogues()

const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const fournisseurs = ref<Fournisseur[]>([])

const recherche = ref('')
const filtreStatut = ref<'ACTIFS' | 'ARCHIVES' | 'TOUS'>('ACTIFS')

const dialog = ref(false)
const formRef = ref()
const editId = ref<number | null>(null)
const nomVerrouille = ref(false)

const canWrite = computed(() =>
  auth.hasAnyRole(['LOGISTIQUE', 'RESP_RESTAURANT', 'RESP_CUISINE', 'RESP_BAR', 'GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'ADMIN']))

const form = reactive({
  nom: '',
  contact: '',
  telephone: '',
  email: '',
  adresse: '',
  nif: '',
  rccm: '',
  compteComptable: '' as string,
})

const rules = {
  nom: [(v: any) => !!v?.trim() || 'Nom ou raison sociale obligatoire'],
  email: [(v: any) => !v || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v) || 'Adresse e-mail invalide'],
}

const normaliser = (s?: string | null) =>
  (s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()

const visibles = computed(() => {
  const q = normaliser(recherche.value.trim())
  return fournisseurs.value.filter((f) => {
    if (filtreStatut.value === 'ACTIFS' && !f.actif) return false
    if (filtreStatut.value === 'ARCHIVES' && f.actif) return false
    if (!q) return true
    return [f.code, f.nom, f.contact, f.telephone, f.email, f.nif, f.rccm, f.compteComptable]
      .some(v => normaliser(v).includes(q))
  })
})

const nombreArchives = computed(() => fournisseurs.value.filter(f => !f.actif).length)

const messageVide = computed(() => {
  if (recherche.value?.trim()) return 'Aucun fournisseur ne correspond à la recherche.'
  if (!fournisseurs.value.length) return 'Aucun fournisseur enregistré.'
  if (filtreStatut.value === 'ACTIFS') return 'Aucun fournisseur actif : les fiches archivées sont sous « Archivés ».'
  if (filtreStatut.value === 'ARCHIVES') return 'Aucun fournisseur archivé.'
  return 'Aucun fournisseur enregistré.'
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    fournisseurs.value = await api<Fournisseur[]>('/fournisseurs')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les fournisseurs.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function ouvrirCreation() {
  editId.value = null
  nomVerrouille.value = false
  Object.assign(form, { nom: '', contact: '', telephone: '', email: '', adresse: '', nif: '', rccm: '', compteComptable: '' })
  erreur.value = ''
  dialog.value = true
}

function ouvrirEdition(f: Fournisseur) {
  editId.value = f.id
  // Les achats renvoient au fournisseur par son nom : le serveur refuse de le changer une fois des achats saisis.
  nomVerrouille.value = f.nombreAchats > 0
  Object.assign(form, {
    nom: f.nom,
    contact: f.contact || '',
    telephone: f.telephone || '',
    email: f.email || '',
    adresse: f.adresse || '',
    nif: f.nif || '',
    rccm: f.rccm || '',
    compteComptable: f.compteComptable || '',
  })
  erreur.value = ''
  dialog.value = true
}

async function enregistrer() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  saving.value = true
  erreur.value = ''
  try {
    const body = {
      nom: form.nom.trim(),
      contact: form.contact.trim() || null,
      telephone: form.telephone.trim() || null,
      email: form.email.trim() || null,
      adresse: form.adresse.trim() || null,
      nif: form.nif.trim() || null,
      rccm: form.rccm.trim() || null,
    }
    if (editId.value) {
      await api(`/fournisseurs/${editId.value}`, { method: 'PUT', body })
      succes.value = `Fournisseur « ${body.nom} » modifié.`
    } else {
      const cree = await api<Fournisseur>('/fournisseurs', { method: 'POST', body })
      succes.value = `Fournisseur « ${cree.nom} » enregistré (${cree.code}).`
    }
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Enregistrement impossible.')
  } finally {
    saving.value = false
  }
}

async function archiver(f: Fournisseur) {
  const ok = await confirmer({
    titre: `Archiver « ${f.nom} » ?`,
    message: [
      'La fiche quitte la liste des fournisseurs actifs mais reste consultable avec le filtre « Archivés ».',
      f.nombreAchats > 0
        ? `Ses ${f.nombreAchats} achat(s) enregistré(s) restent inchangés.`
        : 'Vous pourrez la réactiver à tout moment.',
    ],
    bouton: 'Archiver',
    nature: 'attention',
    icone: 'mdi-archive-arrow-down-outline',
  })
  if (!ok) return
  await action(() => api(`/fournisseurs/${f.id}/archiver`, { method: 'POST' }), `« ${f.nom} » archivé.`)
}

async function reactiver(f: Fournisseur) {
  await action(() => api(`/fournisseurs/${f.id}/reactiver`, { method: 'POST' }), `« ${f.nom} » réactivé.`)
}

async function supprimer(f: Fournisseur) {
  const ok = await confirmer({
    titre: `Supprimer « ${f.nom} » ?`,
    message: 'Aucun achat n\'est enregistré à son nom : la fiche sera effacée définitivement.',
    bouton: 'Supprimer',
    nature: 'danger',
  })
  if (!ok) return
  await action(() => api(`/fournisseurs/${f.id}`, { method: 'DELETE' }), `« ${f.nom} » supprimé.`)
}

async function action(appel: () => Promise<unknown>, message: string) {
  erreur.value = ''
  succes.value = ''
  try {
    await appel()
    succes.value = message
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Action impossible.')
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Fournisseurs</h1>
        <p class="page-sub">Répertoire des fournisseurs · coordonnées, NIF, RCCM et compte propre ouvert sous 4011</p>
      </div>
      <div class="page-head-actions">
        <v-btn variant="outlined" color="primary" prepend-icon="mdi-refresh" rounded="lg" :loading="loading" @click="charger">
          Actualiser
        </v-btn>
        <v-btn v-if="canWrite" color="primary" prepend-icon="mdi-plus" rounded="lg" @click="ouvrirCreation">
          Nouveau fournisseur
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">
      {{ succes }}
    </v-alert>

    <v-card class="classroom-card">
      <div class="frn-filtres">
        <v-text-field
          v-model="recherche"
          label="Rechercher (nom, contact, téléphone, NIF, RCCM, compte)"
          prepend-inner-icon="mdi-magnify"
          variant="outlined" density="compact" rounded="lg" hide-details clearable
          class="frn-recherche"
        />
        <v-btn-toggle v-model="filtreStatut" mandatory density="compact" rounded="lg" color="primary" variant="outlined">
          <v-btn value="ACTIFS">Actifs</v-btn>
          <v-btn value="ARCHIVES">Archivés<span v-if="nombreArchives" class="ml-1">({{ nombreArchives }})</span></v-btn>
          <v-btn value="TOUS">Tous</v-btn>
        </v-btn-toggle>
      </div>

      <v-data-table
        :items="visibles"
        :loading="loading"
        density="comfortable"
        items-per-page="15"
        :headers="[
          { title: 'Code', key: 'code' },
          { title: 'Nom / raison sociale', key: 'nom' },
          { title: 'Contact', key: 'contact' },
          { title: 'Téléphone', key: 'telephone' },
          { title: 'NIF', key: 'nif' },
          { title: 'RCCM', key: 'rccm' },
          { title: 'Compte', key: 'compteComptable' },
          { title: 'Achats', key: 'nombreAchats', align: 'end' },
          { title: 'Statut', key: 'actif', align: 'center' },
          { title: '', key: 'actions', sortable: false, align: 'end' },
        ]"
      >
        <template #[`item.code`]="{ item }">
          <code class="text-caption text-primary">{{ item.code }}</code>
        </template>
        <template #[`item.nom`]="{ item }">
          <div class="font-weight-medium">{{ item.nom }}</div>
          <div v-if="item.email" class="text-caption text-medium-emphasis frn-coupe">{{ item.email }}</div>
        </template>
        <template #[`item.contact`]="{ item }">
          <span :class="item.contact ? '' : 'text-medium-emphasis'">{{ item.contact || '—' }}</span>
        </template>
        <template #[`item.telephone`]="{ item }">
          <span :class="item.telephone ? '' : 'text-medium-emphasis'">{{ item.telephone || '—' }}</span>
        </template>
        <template #[`item.nif`]="{ item }">
          <span :class="item.nif ? '' : 'text-medium-emphasis'">{{ item.nif || '—' }}</span>
        </template>
        <template #[`item.rccm`]="{ item }">
          <span :class="item.rccm ? 'frn-coupe' : 'text-medium-emphasis'">{{ item.rccm || '—' }}</span>
        </template>
        <template #[`item.compteComptable`]="{ item }">
          <code class="text-caption">{{ item.compteComptable }}</code>
        </template>
        <template #[`item.actif`]="{ item }">
          <v-chip :color="item.actif ? 'success' : 'grey'" size="small" variant="tonal">
            {{ item.actif ? 'Actif' : 'Archivé' }}
          </v-chip>
        </template>
        <template #[`item.actions`]="{ item }">
          <div v-if="canWrite" class="frn-actions">
            <v-btn size="small" variant="text" icon="mdi-pencil" title="Modifier" @click="ouvrirEdition(item)" />
            <v-btn v-if="item.actif" size="small" variant="text" icon="mdi-archive-arrow-down-outline" title="Archiver"
              @click="archiver(item)" />
            <v-btn v-else size="small" variant="text" icon="mdi-archive-arrow-up-outline" title="Réactiver"
              @click="reactiver(item)" />
            <v-btn v-if="item.nombreAchats === 0" size="small" variant="text" color="error" icon="mdi-delete-outline"
              title="Supprimer (aucun achat)" @click="supprimer(item)" />
          </div>
        </template>
        <template #no-data>
          <div class="frn-vide">
            <v-icon icon="mdi-handshake-outline" size="42" color="#d1d5db" class="mb-2" />
            <p>{{ messageVide }}</p>
          </div>
        </template>
      </v-data-table>
    </v-card>

    <!-- ── Dialogue ────────────────────────────────────────── -->
    <v-dialog v-model="dialog" max-width="640" persistent scrollable>
      <v-card class="dialog-card" rounded="xl">
        <div class="dialog-header">
          <v-icon icon="mdi-handshake-outline" size="28" color="white" class="mb-2" />
          <div class="text-h6 font-weight-bold text-white">
            {{ editId ? 'Modifier le fournisseur' : 'Nouveau fournisseur' }}
          </div>
        </div>

        <v-card-text class="pa-6 pt-5">
          <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" density="compact">{{ erreur }}</v-alert>

          <v-form ref="formRef">
            <v-text-field
              v-model="form.nom"
              label="Nom ou raison sociale *"
              prepend-inner-icon="mdi-domain"
              variant="outlined" density="comfortable" rounded="lg"
              :rules="rules.nom" :disabled="nomVerrouille" class="mb-3"
              :hint="nomVerrouille ? 'Des achats sont enregistrés à ce nom : il ne peut plus changer.' : ''"
              :persistent-hint="nomVerrouille"
            />
            <v-row dense>
              <v-col cols="12" sm="6">
                <v-text-field
                  v-model="form.contact"
                  label="Personne de contact"
                  prepend-inner-icon="mdi-account-tie-outline"
                  variant="outlined" density="comfortable" rounded="lg"
                />
              </v-col>
              <v-col cols="12" sm="6">
                <v-text-field
                  v-model="form.telephone"
                  label="Téléphone"
                  type="tel"
                  prepend-inner-icon="mdi-phone-outline"
                  variant="outlined" density="comfortable" rounded="lg"
                />
              </v-col>
              <v-col cols="12" sm="6">
                <v-text-field
                  v-model="form.email"
                  label="E-mail"
                  type="email"
                  prepend-inner-icon="mdi-email-outline"
                  variant="outlined" density="comfortable" rounded="lg" :rules="rules.email"
                />
              </v-col>
              <v-col cols="12" sm="6">
                <v-text-field
                  v-model="form.adresse"
                  label="Adresse"
                  prepend-inner-icon="mdi-map-marker-outline"
                  variant="outlined" density="comfortable" rounded="lg"
                />
              </v-col>
              <v-col cols="12" sm="6">
                <v-text-field
                  v-model="form.nif"
                  label="NIF (numéro d'impôt)"
                  prepend-inner-icon="mdi-card-account-details-outline"
                  variant="outlined" density="comfortable" rounded="lg"
                />
              </v-col>
              <v-col cols="12" sm="6">
                <v-text-field
                  v-model="form.rccm"
                  label="RCCM"
                  prepend-inner-icon="mdi-file-certificate-outline"
                  variant="outlined" density="comfortable" rounded="lg"
                />
              </v-col>
              <v-col cols="12">
                <div class="frn-compte">
                  <v-icon icon="mdi-book-open-variant" size="18" class="mr-2" />
                  <span v-if="editId && form.compteComptable">
                    Compte du fournisseur : <code>{{ form.compteComptable }}</code>
                  </span>
                  <span v-else>Son compte (40111, 40112…) est ouvert automatiquement sous 4011 à l'enregistrement.</span>
                </div>
              </v-col>
            </v-row>
          </v-form>
        </v-card-text>

        <v-card-actions class="px-6 pb-5 pt-0 gap-2">
          <v-btn variant="tonal" rounded="lg" :disabled="saving" @click="dialog = false">Annuler</v-btn>
          <v-spacer />
          <v-btn color="primary" variant="flat" rounded="lg" :loading="saving" prepend-icon="mdi-check" @click="enregistrer">
            Enregistrer
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.page-head-actions { display: flex; gap: 10px; flex-wrap: wrap; }

.frn-filtres {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  padding: 16px 16px 8px;
}
.frn-recherche { flex: 1 1 260px; min-width: 0; }
.frn-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; }
.frn-coupe { overflow-wrap: anywhere; }

.frn-compte {
  display: flex;
  align-items: center;
  padding: 10px 14px;
  border-radius: 10px;
  background: rgba(var(--v-theme-primary), 0.06);
  color: rgba(var(--v-theme-on-surface), 0.75);
  font-size: 0.875rem;
}
.frn-vide {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 20px;
  color: var(--ink-faint);
  font-size: 0.85rem;
  text-align: center;
}
.frn-vide p { margin: 0; }

.dialog-card { overflow: hidden; }
.dialog-header {
  padding: 26px 26px 20px;
  text-align: center;
  background: linear-gradient(140deg, #34d399 0%, var(--color-primary) 50%, var(--color-primary-darker) 100%);
}
</style>
