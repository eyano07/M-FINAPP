<script setup lang="ts">
/**
 * Miniature d'une page A4 habillée selon un modèle de papier à en-tête et une couleur : reproduit à
 * petite échelle la mise en page des PDF du serveur (EnteteDocumentRendu) pour guider le choix de
 * l'administrateur. Purement décoratif : le nom du modèle est donné par le bouton qui l'entoure.
 */
import type { ModeleEntete } from '~/composables/useModeleEntete'

const props = defineProps<{
  modele: ModeleEntete
  couleur: string
  nom: string
  logoUrl?: string | null
}>()

const initiale = computed(() => (props.nom || '?').trim().charAt(0).toUpperCase())
</script>

<template>
  <div class="ae" :class="`ae--${modele.toLowerCase()}`" :style="{ '--ae-c': couleur }" aria-hidden="true">
    <div v-if="modele === 'LATERAL'" class="ae-barre" />
    <div class="ae-tete">
      <div v-if="modele === 'CLASSIQUE'" class="ae-ruban">
        <i /><i /><i />
      </div>
      <div class="ae-marque">
        <span class="ae-logo">
          <img v-if="logoUrl" :src="logoUrl" alt="">
          <template v-else>{{ initiale }}</template>
        </span>
        <span class="ae-textes">
          <span class="ae-nom">{{ nom || 'Votre société' }}</span>
          <i class="ae-trait ae-trait--court" />
        </span>
      </div>
      <div v-if="modele !== 'CLASSIQUE'" class="ae-ids"><i /><i /><i /></div>
    </div>
    <div class="ae-corps">
      <i /><i /><i /><i class="ae-trait--court" /><i /><i />
    </div>
    <div class="ae-pied"><i /><i /><i /></div>
  </div>
</template>

<style scoped>
.ae {
  --ae-c: #15803d;
  --ae-fonce: color-mix(in srgb, var(--ae-c) 68%, black);
  --ae-clair: color-mix(in srgb, var(--ae-c) 10%, white);
  position: relative;
  aspect-ratio: 210 / 297;
  width: 100%;
  background: #fff;
  border-radius: 4px;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.16);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  font-family: Helvetica, Arial, sans-serif;
}
.ae i { display: block; height: 3px; border-radius: 2px; background: #e5e7eb; }

/* En-tête */
.ae-tete { position: relative; display: flex; align-items: center; justify-content: space-between; gap: 6px; padding: 9% 9% 4%; }
.ae-marque { display: flex; align-items: center; gap: 5px; min-width: 0; z-index: 1; }
.ae-logo {
  flex-shrink: 0; width: 15px; height: 15px; border-radius: 3px; background: var(--ae-c); color: #fff;
  font-size: 9px; font-weight: 700; display: flex; align-items: center; justify-content: center; overflow: hidden;
}
.ae-logo img { width: 100%; height: 100%; object-fit: contain; background: #fff; }
.ae-textes { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.ae-nom { font-size: 7px; font-weight: 800; color: var(--ae-fonce); text-transform: uppercase; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ae-trait--court { width: 60%; }
.ae-ids { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; width: 26%; flex-shrink: 0; }
.ae-ids i { width: 100%; background: #d1d5db; }
.ae-ids i:nth-child(2) { width: 80%; }

/* Corps */
.ae-corps { flex: 1; display: flex; flex-direction: column; gap: 5px; padding: 6% 9%; }
.ae-corps .ae-trait--court { width: 55%; }

/* Pied */
.ae-pied { display: flex; justify-content: space-between; gap: 8%; padding: 4% 9% 6%; }
.ae-pied i { flex: 1; height: 2px; background: #d1d5db; }

/* Classique : ruban incliné à droite, pied teinté */
.ae--classique .ae-tete { border-bottom: 1px solid #e5e7eb; margin: 0 9%; padding: 9% 0 4%; }
.ae-ruban {
  position: absolute; right: -14%; top: 22%; width: 42%; height: 42%;
  background: linear-gradient(135deg, var(--ae-c), var(--ae-fonce));
  clip-path: polygon(8% 0, 100% 0, 100% 100%, 0 100%);
  display: flex; flex-direction: column; justify-content: center; gap: 2px; padding-left: 12%;
}
.ae-ruban i { background: rgba(255, 255, 255, 0.85); width: 60%; height: 2px; }
.ae--classique .ae-pied { background: var(--ae-clair); }
.ae--classique .ae-pied i { background: var(--ae-c); opacity: 0.45; }

/* Bandeau */
.ae--bandeau .ae-tete { background: var(--ae-c); border-bottom: 2px solid var(--ae-fonce); padding: 8% 9%; }
.ae--bandeau .ae-nom { color: #fff; }
.ae--bandeau .ae-logo { background: #fff; color: var(--ae-c); }
.ae--bandeau .ae-textes i, .ae--bandeau .ae-ids i { background: rgba(255, 255, 255, 0.7); }
.ae--bandeau .ae-pied { background: var(--ae-c); }
.ae--bandeau .ae-pied i { background: rgba(255, 255, 255, 0.7); }

/* Épuré */
.ae--epure .ae-tete { border-bottom: 1px solid var(--ae-c); margin: 0 9%; padding: 9% 0 4%; }
.ae--epure .ae-nom { color: var(--ae-c); }
.ae--epure .ae-logo { background: transparent; color: var(--ae-c); border: 1px solid var(--ae-c); }
.ae--epure .ae-pied { border-top: 1px solid #e5e7eb; margin: 0 9%; padding: 4% 0 6%; }

/* Institutionnel */
.ae--centre .ae-tete { flex-direction: column; border-bottom: 3px double var(--ae-c); margin: 0 9%; padding: 7% 0 4%; }
.ae--centre .ae-marque { flex-direction: column; text-align: center; }
.ae--centre .ae-textes { align-items: center; }
.ae--centre .ae-ids { flex-direction: row; width: 70%; justify-content: center; }
.ae--centre .ae-pied { border-top: 3px double var(--ae-c); margin: 0 9%; padding: 4% 0 6%; }

/* Latéral */
.ae-barre { position: absolute; left: 0; top: 0; bottom: 0; width: 5%; background: var(--ae-c); box-shadow: 2px 0 0 var(--ae-clair); }
.ae--lateral .ae-tete { border-bottom: 1px solid #e5e7eb; margin: 0 9% 0 12%; padding: 9% 0 4%; }
.ae--lateral .ae-nom { color: var(--ae-c); }
.ae--lateral .ae-ids { border-left: 2px solid var(--ae-c); padding-left: 3px; align-items: flex-start; }
.ae--lateral .ae-corps, .ae--lateral .ae-pied { padding-left: 12%; }

/* Encadré */
.ae--encadre .ae-tete { margin: 7% 6% 0; padding: 4%; background: var(--ae-clair); border: 1px solid var(--ae-c); border-radius: 2px; }
.ae--encadre .ae-pied { margin: 0 6% 5%; padding: 3% 4%; background: var(--ae-clair); border: 1px solid var(--ae-c); border-radius: 2px; }
</style>
