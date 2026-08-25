import {
  createRouter,
  createWebHistory,
  type Router,
  type RouterHistory
} from "vue-router";
import { useAuthStore } from "../stores/auth";
import LoginView from "../views/LoginView.vue";

const OverviewView = () => import("../views/OverviewView.vue");
const PlaygroundView = () => import("../views/PlaygroundView.vue");
const CampaignListView = () => import("../views/CampaignListView.vue");
const CampaignStudioView = () => import("../views/CampaignStudioView.vue");
const KnowledgeView = () => import("../views/KnowledgeView.vue");
const IntegrationsView = () => import("../views/IntegrationsView.vue");
const EvaluationLabView = () => import("../views/EvaluationLabView.vue");

export function createAppRouter(
  history: RouterHistory = createWebHistory()
): Router {
  const router = createRouter({
    history,
    routes: [
      {
        path: "/",
        redirect: "/overview"
      },
      {
        path: "/login",
        name: "login",
        component: LoginView
      },
      {
        path: "/overview",
        name: "overview",
        component: OverviewView,
        meta: { requiresAuth: true }
      },
      {
        path: "/campaigns",
        name: "campaigns",
        component: CampaignListView,
        meta: { requiresAuth: true }
      },
      {
        path: "/campaigns/:campaignId",
        name: "campaign-studio",
        component: CampaignStudioView,
        meta: { requiresAuth: true }
      },
      {
        path: "/knowledge",
        name: "knowledge",
        component: KnowledgeView,
        meta: { requiresAuth: true }
      },
      {
        path: "/integrations",
        name: "integrations",
        component: IntegrationsView,
        meta: { requiresAuth: true }
      },
      {
        path: "/playground",
        name: "playground",
        component: PlaygroundView,
        meta: { requiresAuth: true }
      },
      {
        path: "/evaluations",
        name: "evaluations",
        component: EvaluationLabView,
        meta: { requiresAuth: true }
      }
    ]
  });

  router.beforeEach((to) => {
    const auth = useAuthStore();
    if (to.meta.requiresAuth && !auth.isAuthenticated) {
      return {
        path: "/login",
        query: { redirect: to.fullPath }
      };
    }
    if (to.path === "/login" && auth.isAuthenticated) {
      return "/overview";
    }
    return true;
  });

  return router;
}
