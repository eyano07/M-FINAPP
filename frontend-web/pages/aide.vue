<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'
import { useRolesStore } from '~/stores/roles'
import { AIDE_COMMUNE, FICHES_AIDE } from '~/utils/aideRoles'

// Accessible à tout compte connecté, comme /profil : l'aide s'adapte aux rôles du compte.
definePageMeta({})

const auth = useAuthStore()
const rolesStore = useRolesStore()

const mesFiches = computed(() => FICHES_AIDE.filter(f => auth.hasRole(f.role)))
// L'administrateur, qui crée les comptes, peut lire l'aide de chaque rôle pour accompagner les utilisateurs.
const autresFiches = computed(() => auth.hasRole('ADMIN') ? FICHES_AIDE.filter(f => !auth.hasRole(f.role)) : [])
const voirAutres = ref(false)
const recherche = ref('')

function correspond(texte: string) {
  const q = recherche.value.trim().toLowerCase()
  return !q || texte.toLowerCase().includes(q)
}
function filtrer<T extends { titre: string; texte: string }>(liste: T[]) {
  return liste.filter(e => correspond(e.titre + ' ' + e.texte))
}
</script>

<template>
  <div class="aide">
    <header class="aide__entete">
      <div>
        <h1 class="aide__titre">Aide</h1>
        <p class="aide__sous-titre">Ce que vous pouvez faire dans M-FINAPP, selon votre rôle.</p>
      </div>
      <v-text-field
        v-model="recherche"
        class="aide__recherche"
        prepend-inner-icon="mdi-magnify"
        placeholder="Rechercher dans l'aide"
        density="comfortable"
        variant="outlined"
        rounded="lg"
        hide-details
        clearable
      />
    </header>

    <section v-for="f in mesFiches" :key="f.role" class="aide__fiche">
      <div class="aide__fiche-entete">
        <span class="aide__icone"><v-icon :icon="f.icone" size="24" /></span>
        <div>
          <p class="aide__role">{{ rolesStore.libelle(f.role) }}</p>
          <p class="aide__resume">{{ f.resume }}</p>
        </div>
      </div>
      <ol class="aide__taches">
        <li v-for="t in filtrer(f.taches)" :key="t.titre">
          <strong>{{ t.titre }}</strong>
          <span>{{ t.texte }}</span>
        </li>
      </ol>
      <div v-if="f.limites?.length && !recherche" class="aide__limites">
        <v-icon icon="mdi-information-outline" size="18" />
        <ul>
          <li v-for="l in f.limites" :key="l">{{ l }}</li>
        </ul>
      </div>
    </section>

    <section class="aide__fiche">
      <div class="aide__fiche-entete">
        <span class="aide__icone aide__icone--neutre"><v-icon icon="mdi-account-circle-outline" size="24" /></span>
        <div>
          <p class="aide__role">Pour tous les utilisateurs</p>
          <p class="aide__resume">Votre compte, les notifications et l'application mobile.</p>
        </div>
      </div>
      <ol class="aide__taches">
        <li v-for="t in filtrer(AIDE_COMMUNE)" :key="t.titre">
          <strong>{{ t.titre }}</strong>
          <span>{{ t.texte }}</span>
        </li>
      </ol>
    </section>

    <template v-if="autresFiches.length">
      <button type="button" class="aide__autres" @click="voirAutres = !voirAutres">
        <v-icon :icon="voirAutres ? 'mdi-chevron-up' : 'mdi-chevron-down'" size="20" />
        {{ voirAutres ? "Masquer l'aide des autres rôles" : "Voir l'aide des autres rôles" }}
      </button>
      <template v-if="voirAutres">
        <section v-for="f in autresFiches" :key="f.role" class="aide__fiche">
          <div class="aide__fiche-entete">
            <span class="aide__icone aide__icone--neutre"><v-icon :icon="f.icone" size="24" /></span>
            <div>
              <p class="aide__role">{{ rolesStore.libelle(f.role) }}</p>
              <p class="aide__resume">{{ f.resume }}</p>
            </div>
          </div>
          <ol class="aide__taches">
            <li v-for="t in filtrer(f.taches)" :key="t.titre">
              <strong>{{ t.titre }}</strong>
              <span>{{ t.texte }}</span>
            </li>
          </ol>
        </section>
      </template>
    </template>

    <NuxtLink to="/a-propos" class="aide__contact">
      <v-icon icon="mdi-lifebuoy" size="20" />
      <span>Besoin d'aide supplémentaire ? Contactez l'équipe (téléphone, WhatsApp).</span>
      <v-icon icon="mdi-chevron-right" size="20" />
    </NuxtLink>
  </div>
</template>

<style scoped>
.aide { max-width: 860px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }
.aide__entete { display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 12px; }
.aide__titre { font-size: 26px; font-weight: 750; margin: 0; color: var(--ink); }
.aide__sous-titre { margin: 2px 0 0; color: var(--ink-soft); }
.aide__recherche { flex: 1 1 260px; max-width: 360px; }
.aide__fiche { background: var(--surface); border: 1px solid var(--hairline); border-radius: 18px; padding: 18px; }
.aide__fiche-entete { display: flex; gap: 14px; align-items: flex-start; margin-bottom: 12px; }
.aide__icone {
  flex: 0 0 auto; width: 46px; height: 46px; border-radius: 14px; display: grid; place-items: center;
  background: color-mix(in srgb, var(--color-primary) 14%, transparent); color: var(--color-primary);
}
.aide__icone--neutre { background: var(--surface-2); color: var(--ink-soft); }
.aide__role { font-size: 17px; font-weight: 700; margin: 0; color: var(--ink); }
.aide__resume { margin: 2px 0 0; color: var(--ink-soft); font-size: 14px; line-height: 1.45; }
.aide__taches { margin: 0; padding: 0; list-style: none; counter-reset: etape; display: flex; flex-direction: column; gap: 10px; }
.aide__taches li {
  counter-increment: etape; position: relative; padding: 10px 12px 10px 46px; border-radius: 12px; background: var(--surface-2);
  display: flex; flex-direction: column; gap: 2px; font-size: 14px; line-height: 1.5; color: var(--ink-2);
}
.aide__taches li::before {
  content: counter(etape); position: absolute; left: 12px; top: 10px; width: 24px; height: 24px; border-radius: 50%;
  display: grid; place-items: center; font-size: 12px; font-weight: 700; color: #fff; background: var(--color-primary);
}
.aide__taches strong { color: var(--ink); font-weight: 650; }
.aide__limites {
  display: flex; gap: 10px; margin-top: 12px; padding: 10px 12px; border-radius: 12px; font-size: 13.5px; color: var(--ink-soft);
  background: color-mix(in srgb, #b26a00 10%, transparent);
}
.aide__limites ul { margin: 0; padding-left: 16px; }
.aide__autres {
  align-self: center; display: inline-flex; align-items: center; gap: 6px; border: 0; background: transparent;
  color: var(--color-primary); font-weight: 650; padding: 8px 12px; border-radius: 999px;
}
.aide__contact {
  display: flex; align-items: center; gap: 12px; padding: 14px 16px; border-radius: 16px; text-decoration: none;
  color: var(--ink); background: color-mix(in srgb, var(--color-primary) 8%, var(--surface));
  border: 1px solid color-mix(in srgb, var(--color-primary) 22%, var(--hairline));
}
.aide__contact span { flex: 1 1 auto; font-size: 14px; }
</style>
