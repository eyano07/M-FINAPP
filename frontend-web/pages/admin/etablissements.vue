<script setup lang="ts">
definePageMeta({ roles: ['ADMIN'] })

interface Etablissement {
  id: number
  nom: string
  type: 'BANQUE' | 'MOBILE_MONEY'
  compteNumero: string
  compteLibelle: string
  actif: boolean
  solde: number
  supprimable: boolean
}

const api = useApi()

const loading = ref(false)
const erreur = ref('')
const etablissements = ref<Etablissement[]>([])

// Dialog de création
const dialogOuvert = ref(false)
const formRef = ref()
const envoi = ref(false)
const erreurEnvoi = ref('')
const form = reactive({
  nom: '',
  type: 'BANQUE' as 'BANQUE' | 'MOBILE_MONEY',
})
const rules = {
  nom: [(v: any) => !!v?.trim() || 'Nom obligatoire'],
}

// Dialog de suppression
const dialogSuppression = ref(false)
const aSupprimer = ref<Etablissement | null>(null)
const suppressionEnCours = ref(false)

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    etablissements.value = await api<Etablissement[]>('/etablissements')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les établissements.')
  } finally {
    loading.value = false
  }
}

onMounted(charger)

const banques = computed(() => etablissements.value.filter((e) => e.type === 'BANQUE'))
const operateurs = computed(() => etablissements.value.filter((e) => e.type === 'MOBILE_MONEY'))

function ouvrirDialog(type: 'BANQUE' | 'MOBILE_MONEY') {
  form.nom = ''
  form.type = type
  erreurEnvoi.value = ''
  dialogOuvert.value = true
}

async function creer() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  envoi.value = true
  erreurEnvoi.value = ''
  try {
    await api('/etablissements', {
      method: 'POST',
      body: { nom: form.nom.trim(), type: form.type },
    })
    dialogOuvert.value = false
    await charger()
  } catch (e: any) {
    erreurEnvoi.value = messageErreurApi(e, "Erreur lors de la création.")
  } finally {
    envoi.value = false
  }
}

function demanderSuppression(e: Etablissement) {
  aSupprimer.value = e
  erreur.value = ''
  dialogSuppression.value = true
}

async function confirmerSuppression() {
  if (!aSupprimer.value) return
  suppressionEnCours.value = true
  try {
    await api(`/etablissements/${aSupprimer.value.id}`, { method: 'DELETE' })
    dialogSuppression.value = false
    aSupprimer.value = null
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Suppression impossible.')
    dialogSuppression.value = false
  } finally {
    suppressionEnCours.value = false
  }
}

// Le grand livre est tenu en USD (devise de base) : le solde de chaque
// établissement est déjà exprimé en USD, aucune conversion à faire.
const fmtUSD = (usd: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(usd || 0)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Banques &amp; Mobile Money</h1>
        <p class="page-sub">
          Établissements par lesquels transite la trésorerie · chacun possède son propre compte et son propre solde
        </p>
      </div>
      <div class="page-head-actions">
        <v-btn variant="outlined" color="primary" prepend-icon="mdi-refresh" rounded="lg" @click="charger">
          Actualiser
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-row>
      <!-- ── Banques ────────────────────────────────────────────────── -->
      <v-col cols="12" md="6">
        <v-card class="classroom-card">
          <v-card-title class="text-subtitle-1 font-weight-semibold pa-4 pb-2 d-flex align-center">
            <v-icon icon="mdi-bank" size="20" class="mr-2" color="blue" />
            Banques
            <v-chip v-if="banques.length" size="small" color="blue" variant="tonal" class="ml-2">
              {{ banques.length }}
            </v-chip>
            <v-spacer />
            <v-btn
              size="small"
              color="primary"
              variant="flat"
              rounded="lg"
              prepend-icon="mdi-plus"
              @click="ouvrirDialog('BANQUE')"
            >
              Ajouter
            </v-btn>
          </v-card-title>

          <div v-if="loading" class="pa-6 text-center">
            <v-progress-circular indeterminate color="primary" size="28" />
          </div>
          <div v-else-if="banques.length === 0" class="etab-empty">
            <v-icon icon="mdi-bank-outline" size="28" color="#d1d5db" />
            <p>Aucune banque configurée.</p>
          </div>
          <div v-else class="etab-liste">
            <div v-for="e in banques" :key="e.id" class="etab">
              <div class="etab__main">
                <p class="etab__nom">{{ e.nom }}</p>
                <code class="etab__compte">{{ e.compteNumero }}</code>
              </div>
              <div class="etab__solde" :class="e.solde === 0 ? 'etab__solde--nul' : ''">
                {{ fmtUSD(e.solde) }}
              </div>
              <v-tooltip
                :text="e.supprimable
                  ? 'Retirer cette banque'
                  : 'Solde non nul : soldez d\'abord la trésorerie de cette banque'"
                location="top"
              >
                <template #activator="{ props }">
                  <div v-bind="props">
                    <v-btn
                      icon="mdi-delete-outline"
                      variant="text"
                      color="error"
                      size="small"
                      :disabled="!e.supprimable"
                      @click="demanderSuppression(e)"
                    />
                  </div>
                </template>
              </v-tooltip>
            </div>
          </div>
        </v-card>
      </v-col>

      <!-- ── Opérateurs mobile money ────────────────────────────────── -->
      <v-col cols="12" md="6">
        <v-card class="classroom-card">
          <v-card-title class="text-subtitle-1 font-weight-semibold pa-4 pb-2 d-flex align-center">
            <v-icon icon="mdi-cellphone" size="20" class="mr-2" color="deep-orange" />
            Opérateurs mobile money
            <v-chip v-if="operateurs.length" size="small" color="deep-orange" variant="tonal" class="ml-2">
              {{ operateurs.length }}
            </v-chip>
            <v-spacer />
            <v-btn
              size="small"
              color="primary"
              variant="flat"
              rounded="lg"
              prepend-icon="mdi-plus"
              @click="ouvrirDialog('MOBILE_MONEY')"
            >
              Ajouter
            </v-btn>
          </v-card-title>

          <div v-if="loading" class="pa-6 text-center">
            <v-progress-circular indeterminate color="primary" size="28" />
          </div>
          <div v-else-if="operateurs.length === 0" class="etab-empty">
            <v-icon icon="mdi-cellphone-off" size="28" color="#d1d5db" />
            <p>Aucun opérateur configuré.</p>
          </div>
          <div v-else class="etab-liste">
            <div v-for="e in operateurs" :key="e.id" class="etab">
              <div class="etab__main">
                <p class="etab__nom">{{ e.nom }}</p>
                <code class="etab__compte etab__compte--mm">{{ e.compteNumero }}</code>
              </div>
              <div class="etab__solde" :class="e.solde === 0 ? 'etab__solde--nul' : ''">
                {{ fmtUSD(e.solde) }}
              </div>
              <v-tooltip
                :text="e.supprimable
                  ? 'Retirer cet opérateur'
                  : 'Solde non nul : soldez d\'abord la trésorerie de cet opérateur'"
                location="top"
              >
                <template #activator="{ props }">
                  <div v-bind="props">
                    <v-btn
                      icon="mdi-delete-outline"
                      variant="text"
                      color="error"
                      size="small"
                      :disabled="!e.supprimable"
                      @click="demanderSuppression(e)"
                    />
                  </div>
                </template>
              </v-tooltip>
            </div>
          </div>
        </v-card>
      </v-col>
    </v-row>

    <v-alert type="info" variant="tonal" class="mt-4" density="comfortable">
      <span class="text-body-2">
        Un établissement ne peut être retiré que si son solde est nul : la trésorerie doit d'abord être
        transférée ou soldée. Son compte comptable est conservé s'il porte déjà des écritures.
      </span>
    </v-alert>

    <!-- ── Dialog création ──────────────────────────────────────────── -->
    <v-dialog v-model="dialogOuvert" max-width="460" persistent>
      <v-card class="dialog-card" rounded="xl">
        <div class="dialog-header" :class="form.type === 'BANQUE' ? 'dialog-header--banque' : 'dialog-header--mm'">
          <v-icon :icon="form.type === 'BANQUE' ? 'mdi-bank' : 'mdi-cellphone'" size="28" color="white" class="mb-2" />
          <div class="text-h6 font-weight-bold text-white">
            {{ form.type === 'BANQUE' ? 'Nouvelle banque' : 'Nouvel opérateur mobile money' }}
          </div>
          <div class="text-caption text-white" style="opacity:0.85">
            Un compte de trésorerie dédié lui sera automatiquement attribué
          </div>
        </div>

        <v-card-text class="pa-6 pt-5">
          <v-alert v-if="erreurEnvoi" type="error" variant="tonal" class="mb-4" density="compact">
            {{ erreurEnvoi }}
          </v-alert>

          <v-form ref="formRef">
            <v-text-field
              v-model="form.nom"
              :label="form.type === 'BANQUE' ? 'Nom de la banque *' : 'Nom de l\'opérateur *'"
              :placeholder="form.type === 'BANQUE' ? 'ex : Equity Bank' : 'ex : Vodacom'"
              prepend-inner-icon="mdi-tag-outline"
              variant="outlined"
              density="comfortable"
              rounded="lg"
              :rules="rules.nom"
            />
          </v-form>
        </v-card-text>

        <v-card-actions class="px-6 pb-5 pt-0 gap-2">
          <v-btn variant="tonal" rounded="lg" :disabled="envoi" @click="dialogOuvert = false">Annuler</v-btn>
          <v-spacer />
          <v-btn color="primary" variant="flat" rounded="lg" :loading="envoi" prepend-icon="mdi-check" @click="creer">
            Ajouter
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- ── Dialog suppression ───────────────────────────────────────── -->
    <v-dialog v-model="dialogSuppression" max-width="440">
      <v-card rounded="xl">
        <v-card-title class="text-subtitle-1 font-weight-bold pa-5 pb-2">
          Retirer « {{ aSupprimer?.nom }} » ?
        </v-card-title>
        <v-card-text class="px-5">
          <p class="text-body-2 text-medium-emphasis mb-0">
            Son solde est nul, le retrait est donc sans effet sur la trésorerie.
            Il ne sera plus proposé lors des opérations.
          </p>
        </v-card-text>
        <v-card-actions class="px-5 pb-4 pt-2">
          <v-btn variant="tonal" rounded="lg" :disabled="suppressionEnCours" @click="dialogSuppression = false">
            Annuler
          </v-btn>
          <v-spacer />
          <v-btn
            color="error"
            variant="flat"
            rounded="lg"
            :loading="suppressionEnCours"
            prepend-icon="mdi-delete-outline"
            @click="confirmerSuppression"
          >
            Retirer
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}
.page-head-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }

.etab-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 20px;
  color: #9ca3af;
  font-size: 0.85rem;
  text-align: center;
}
.etab-empty p { margin: 0; }

.etab-liste { display: flex; flex-direction: column; }
.etab {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 20px;
  border-top: 1px solid #f3f4f6;
}
.etab__main { flex: 1; min-width: 0; }
.etab__nom {
  font-size: 0.9rem;
  font-weight: 600;
  color: #111827;
  margin: 0 0 2px;
}
.etab__compte {
  font-size: 0.7rem;
  font-weight: 700;
  color: #2563eb;
  background: #eff6ff;
  padding: 1px 6px;
  border-radius: 5px;
}
.etab__compte--mm { color: #ea580c; background: #fff7ed; }
.etab__solde {
  font-size: 0.9rem;
  font-weight: 700;
  color: #111827;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.etab__solde--nul { color: #9ca3af; font-weight: 500; }

.dialog-card { overflow: hidden; }
.dialog-header { padding: 26px 26px 20px; text-align: center; }
.dialog-header--banque { background: linear-gradient(140deg, #60a5fa 0%, #2563eb 50%, #1e3a8a 100%); }
.dialog-header--mm { background: linear-gradient(140deg, #fb923c 0%, #ea580c 50%, #7c2d12 100%); }
</style>
