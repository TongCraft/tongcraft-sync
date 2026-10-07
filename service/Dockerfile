FROM node:24.13.0-bookworm-slim
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev --ignore-scripts
COPY src ./src
RUN mkdir /app/data && chown node:node /app/data
USER node
ENV HOST=0.0.0.0 PORT=8787 TONGCRAFT_DATA_DIR=/app/data
EXPOSE 8787
CMD ["node", "src/main.js"]
