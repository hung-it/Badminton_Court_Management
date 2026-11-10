FROM node:22-alpine AS build
ARG WEB_DIR
WORKDIR /app
COPY ${WEB_DIR}/package.json ${WEB_DIR}/package-lock.json ./
RUN npm ci
COPY ${WEB_DIR}/ ./
RUN npm run build

FROM nginx:stable-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
