FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# 카카오 JavaScript 키는 번들에 박히므로 빌드 때 받는다(도메인 등록으로 보호)
ARG VITE_KAKAO_JS_KEY
ENV VITE_KAKAO_JS_KEY=$VITE_KAKAO_JS_KEY
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
