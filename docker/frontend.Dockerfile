FROM nginx:alpine

# Remove default nginx config
RUN rm /etc/nginx/conf.d/default.conf

# Copy our nginx config and static files
COPY docker/nginx.conf        /etc/nginx/conf.d/default.conf
COPY frontend/index.html      /usr/share/nginx/html/
COPY frontend/app.js          /usr/share/nginx/html/
COPY frontend/styles.css      /usr/share/nginx/html/

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
