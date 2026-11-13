import { createApp } from "vue";
import App from "./App.vue";
import "./style.css";
import { initializeAppearance } from "./appearance";
import "./appearance.css";
import "./product.css";
initializeAppearance();
createApp(App).mount("#app");
