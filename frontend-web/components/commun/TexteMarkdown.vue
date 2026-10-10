<script lang="ts">
/**
 * Affiche un texte en Markdown simple (réponses de l'IA) : titres, gras, italique, code, listes, tableaux.
 *
 * Le texte est analysé en arbre puis rendu avec des éléments Vue et des nœuds texte : rien n'est jamais interprété
 * comme du HTML (pas de v-html), donc une réponse piégée ne peut injecter ni balise ni script.
 * `enligne` : texte court sans mise en page (une puce, un avertissement) ; seuls gras, italique et code sont rendus.
 */
import { defineComponent, h, type VNode } from 'vue'
import { analyserMarkdown, enLigne, type Bloc, type Inline } from '~/utils/markdownLite'

function noeuds(liste: Inline[]): (VNode | string)[] {
  return liste.map((n) => {
    switch (n.t) {
      case 'texte': return n.v
      case 'saut': return h('br')
      case 'gras': return h('strong', noeuds(n.c))
      case 'italique': return h('em', noeuds(n.c))
      case 'code': return h('code', n.v)
    }
  })
}

function blocs(liste: Bloc[]): VNode[] {
  return liste.map((b) => {
    switch (b.t) {
      case 'titre': return h('p', { class: ['md-titre', `md-titre--${Math.min(b.niveau, 3)}`] }, noeuds(b.c))
      case 'para': return h('p', { class: 'md-para' }, noeuds(b.c))
      case 'regle': return h('hr', { class: 'md-regle' })
      case 'codebloc': return h('pre', { class: 'md-codebloc' }, b.v)
      case 'liste':
        return h(b.ordonnee ? 'ol' : 'ul', { class: 'md-liste' }, b.items.map(item =>
          h('li', [...noeuds(item.c), ...blocs(item.sous)])))
      case 'tableau':
        return h('div', { class: 'md-tableau-wrap' }, [h('table', { class: 'md-tableau' }, [
          h('thead', [h('tr', b.entete.map(c => h('th', noeuds(c))))]),
          h('tbody', b.lignes.map(l => h('tr', l.map(c => h('td', noeuds(c)))))),
        ])])
    }
  })
}

export default defineComponent({
  name: 'CommunTexteMarkdown',
  props: {
    texte: { type: String, default: '' },
    enligne: { type: Boolean, default: false },
  },
  setup(props) {
    return () => props.enligne
      ? h('span', { class: 'md' }, noeuds(enLigne((props.texte ?? '').replace(/\s*\n\s*/g, ' '))))
      : h('div', { class: 'md' }, blocs(analyserMarkdown(props.texte)))
  },
})
</script>

<style scoped>
.md { line-height: 1.55; overflow-wrap: anywhere; }
.md-para { margin: 0 0 8px; }
.md-titre { margin: 12px 0 4px; font-weight: 700; }
.md-titre--1 { font-size: 1.05rem; }
.md-titre--2 { font-size: 1rem; }
.md-titre--3 { font-size: 0.95rem; }
.md-liste { margin: 0 0 8px; padding-left: 20px; }
.md-liste .md-liste { margin: 2px 0 2px; }
.md-liste li { margin: 2px 0; }
.md-regle { border: 0; border-top: 1px solid #d1d5db; margin: 10px 0; }
.md :deep(code), .md-codebloc { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 0.85em; background: #e8ecea; border-radius: 4px; }
.md :deep(code) { padding: 1px 4px; }
.md-codebloc { padding: 8px 10px; margin: 0 0 8px; white-space: pre-wrap; }
.md-tableau-wrap { overflow-x: auto; margin: 0 0 8px; }
.md-tableau { border-collapse: collapse; font-size: 0.85em; }
.md-tableau th, .md-tableau td { border: 1px solid #d1d5db; padding: 4px 8px; text-align: left; }
.md-tableau th { background: #e8ecea; font-weight: 700; }
.md > :last-child { margin-bottom: 0; }
</style>
