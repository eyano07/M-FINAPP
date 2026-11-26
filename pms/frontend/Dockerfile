# ---- Build stage ----
FROM node:20-alpine AS build
WORKDIR /app
# Le lockfile est copie avec le manifeste : sans lui, "npm install" re-resolvait
# tout l'arbre a chaque build, si bien que la publication d'une version
# transitive suffisait a casser un build jusque-la vert (npm 10 echouait sur
# « Cannot read properties of null (reading 'edgesOut') »). "npm ci" installe
# exactement l'arbre verrouille et rend le build reproductible.
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- Runtime stage ----
FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production
ENV NITRO_PORT=3000
ENV NITRO_HOST=0.0.0.0
# Execution sans privileges (utilisateur "node" fourni par l'image).
COPY --from=build --chown=node:node /app/.output ./.output
USER node
EXPOSE 3000
CMD ["node", ".output/server/index.mjs"]
