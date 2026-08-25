import { createPinia } from "pinia";
import { createApp } from "vue";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import App from "./App.vue";
import { registerUnauthorizedHandler } from "./api/http";
import { createAppRouter } from "./router";
import { useAuthStore } from "./stores/auth";
import "./styles/main.css";

const app = createApp(App);
const pinia = createPinia();
const router = createAppRouter();

app.use(pinia);
app.use(router);
app.use(ElementPlus);

registerUnauthorizedHandler(() => {
  const auth = useAuthStore(pinia);
  auth.logout();
  void router.push({
    path: "/login",
    query: { redirect: router.currentRoute.value.fullPath }
  });
});

app.mount("#app");
