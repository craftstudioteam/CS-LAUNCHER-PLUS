package net.kdt.pojavlaunch.sponsor;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

/**
 * Official Partners page — Infrawire presented as the launcher's Official
 * Hosting Partner. Premium, calm and informative; every action opens the
 * device browser (no WebView, no interruptions, no forced ads).
 */
public class InfrawirePartnerFragment extends Fragment {

    public static final String TAG = "InfrawirePartnerFragment";

    // ── Copy deck ──────────────────────────────────────────────────────────

    private static final String[][] BENEFITS = {
            {"99.99% Uptime", "Always-on infrastructure"},
            {"24/7 Expert Support", "Real humans, any time"},
            {"Global Datacenters", "Low ping, everywhere"},
            {"Enterprise Hardware", "Latest-gen platforms"},
            {"10 Gbps Network", "Blazing throughput"},
            {"Unlimited Traffic", "No bandwidth caps"},
            {"Anti-DDoS Protection", "Multi-layer mitigation"},
            {"Fast Deployment", "Online in ~55 seconds"},
            {"Free Migration", "We move your server"},
            {"Custom Configurations", "Build your own plan"},
    };

    private static class Plan {
        final String name, cpu, ram, disk, price;
        Plan(String name, String cpu, String ram, String disk, String price) {
            this.name = name; this.cpu = cpu; this.ram = ram; this.disk = disk; this.price = price;
        }
    }

    private static final Plan[] PLANS = {
            new Plan("Mini",     "1 vCore",  "2 GB",  "10 GB NVMe",  "$4.83/month"),
            new Plan("Starter",  "2 vCore",  "4 GB",  "25 GB NVMe",  "$7.73/month"),
            new Plan("Plus",     "4 vCore",  "8 GB",  "50 GB NVMe",  "$19.33/month"),
            new Plan("Max",      "6 vCore",  "16 GB", "70 GB NVMe",  "$31.91/month"),
            new Plan("Flex",     "8 vCore",  "24 GB", "100 GB NVMe", "$46.42/month"),
            new Plan("Ultimate", "12 vCore", "32 GB", "120 GB NVMe", "$67.70/month"),
    };

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_infrawire_partner, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Back
        View back = view.findViewById(R.id.infrawire_back_button);
        if (back != null) back.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

        // CTAs
        bindLink(view, R.id.infrawire_btn_deploy, InfrawirePartner.URL_VPS);
        bindLink(view, R.id.infrawire_btn_learn_more, InfrawirePartner.URL_WEBSITE);
        bindLink(view, R.id.infrawire_btn_view_plans, InfrawirePartner.URL_VPS);
        bindLink(view, R.id.infrawire_btn_promotions, InfrawirePartner.URL_PROMOTIONS);
        bindLink(view, R.id.infrawire_link_website, InfrawirePartner.URL_WEBSITE);
        bindLink(view, R.id.infrawire_link_cloud, InfrawirePartner.URL_CLOUD);
        bindLink(view, R.id.infrawire_link_docs, InfrawirePartner.URL_DOCS);
        bindLink(view, R.id.infrawire_link_support, InfrawirePartner.URL_SUPPORT);

        // Benefits grid (10 premium tiles, 2 columns)
        buildBenefitsGrid(view);

        // VPS plan comparison list
        RecyclerView plans = view.findViewById(R.id.infrawire_plans_recycler);
        plans.setLayoutManager(new LinearLayoutManager(requireContext()));
        plans.setAdapter(new PlansAdapter());

        // Soft staggered entrance — premium, never flashy
        InfrawirePartner.fadeIn(view.findViewById(R.id.infrawire_top_bar), 0);
        InfrawirePartner.fadeIn(view.findViewById(R.id.infrawire_hero_card), 60);
        InfrawirePartner.fadeIn(view.findViewById(R.id.infrawire_benefits_title), 120);
        InfrawirePartner.fadeIn(view.findViewById(R.id.infrawire_benefits_grid), 160);
        InfrawirePartner.fadeIn(view.findViewById(R.id.infrawire_plans_header), 220);
        InfrawirePartner.fadeIn(view.findViewById(R.id.infrawire_plans_recycler), 260);
        InfrawirePartner.fadeIn(view.findViewById(R.id.infrawire_promo_card), 320);
        InfrawirePartner.fadeIn(view.findViewById(R.id.infrawire_links_row), 380);
    }

    private void bindLink(@NonNull View root, int id, @NonNull String url) {
        View target = root.findViewById(id);
        if (target == null) return;
        InfrawirePartner.applyPressAnimation(target);
        target.setOnClickListener(v -> InfrawirePartner.openLink(requireContext(), url));
    }

    private void buildBenefitsGrid(@NonNull View root) {
        GridLayout grid = root.findViewById(R.id.infrawire_benefits_grid);
        if (grid == null) return;
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        int margin = dp(5);
        for (int i = 0; i < BENEFITS.length; i++) {
            String[] benefit = BENEFITS[i];
            View tile = inflater.inflate(R.layout.item_infrawire_benefit, grid, false);
            TextView title = tile.findViewById(R.id.tv_benefit_title);
            TextView sub = tile.findViewById(R.id.tv_benefit_sub);
            title.setText(benefit[0]);
            sub.setText(benefit[1]);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(margin, margin, margin, margin);
            tile.setLayoutParams(lp);

            InfrawirePartner.applyPressAnimation(tile);
            final String url = InfrawirePartner.URL_WEBSITE;
            tile.setOnClickListener(v -> InfrawirePartner.openLink(requireContext(), url));

            grid.addView(tile);
            InfrawirePartner.fadeIn(tile, 200L + i * 35L);
        }
        // Tiles already stretch to their column via weighted columnSpec — no
        // extra gravity call needed (framework GridLayout has none).
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ── Plans adapter ──────────────────────────────────────────────────────

    private class PlansAdapter extends RecyclerView.Adapter<PlansAdapter.PlanHolder> {

        @NonNull
        @Override
        public PlanHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_infrawire_plan, parent, false);
            return new PlanHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull PlanHolder holder, int position) {
            Plan plan = PLANS[position];
            holder.name.setText(plan.name);
            holder.price.setText(plan.price);
            holder.cpu.setText(plan.cpu);
            holder.ram.setText(plan.ram);
            holder.disk.setText(plan.disk);
            InfrawirePartner.applyPressAnimation(holder.card);
            holder.card.setOnClickListener(v ->
                    InfrawirePartner.openLink(requireContext(), InfrawirePartner.URL_VPS));
        }

        @Override
        public int getItemCount() {
            return PLANS.length;
        }

        class PlanHolder extends RecyclerView.ViewHolder {
            final View card;
            final TextView name, price, cpu, ram, disk;
            PlanHolder(@NonNull View itemView) {
                super(itemView);
                card = itemView.findViewById(R.id.infrawire_plan_card);
                name = itemView.findViewById(R.id.tv_plan_name);
                price = itemView.findViewById(R.id.tv_plan_price);
                cpu = itemView.findViewById(R.id.tv_plan_cpu);
                ram = itemView.findViewById(R.id.tv_plan_ram);
                disk = itemView.findViewById(R.id.tv_plan_disk);
            }
        }
    }
}
