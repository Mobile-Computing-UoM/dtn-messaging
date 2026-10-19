#!/usr/bin/env python3
import sys
import os
import pandas as pd
import matplotlib.pyplot as plt

def main():
    # Detect the most recently modified CSV
    csv_candidates = ["simulation_results.csv", "simulator/simulation_results.csv"]
    existing = [f for f in csv_candidates if os.path.exists(f)]
    if not existing:
        print("Error: simulation_results.csv not found.")
        sys.exit(1)

    csv_file = max(existing, key=os.path.getmtime)
    if len(sys.argv) > 1 and os.path.exists(sys.argv[1]):
        csv_file = sys.argv[1]

    print(f"Loading results from {csv_file}...")
    df = pd.read_csv(csv_file)

    suites = df['suite'].unique()
    strategies = df['strategy'].unique()

    fig, axes = plt.subplots(2, 2, figsize=(16, 10), dpi=300)

    # Style mapping for consistency
    colors = {
        'DIRECT_DELIVERY': '#d9534f',
        'EPIDEMIC': '#f0ad4e',
        'SPRAY_AND_WAIT': '#5cb85c'
    }
    markers = {
        'DIRECT_DELIVERY': 'o',
        'EPIDEMIC': 's',
        'SPRAY_AND_WAIT': '^'
    }

    # 1. Delivery Ratio (%)
    ax1 = axes[0, 0]
    for strat in strategies:
        strat_df = df[df['strategy'] == strat]
        c = colors.get(strat, None)
        m = markers.get(strat, 'o')
        ax1.plot(strat_df['suite'], strat_df['delivery_ratio'] * 100, marker=m, color=c, label=strat, linewidth=2)
    ax1.set_title("Delivery Ratio (%)", fontsize=12, fontweight='bold')
    ax1.set_ylabel("Delivery Ratio (%)")
    ax1.set_ylim(-5, 105)
    ax1.grid(True, linestyle='--', alpha=0.5)
    ax1.tick_params(axis='x', rotation=35)
    ax1.legend(loc='lower right')

    # 2. Average Delivery Delay (seconds)
    ax2 = axes[0, 1]
    for strat in strategies:
        strat_df = df[df['strategy'] == strat]
        c = colors.get(strat, None)
        m = markers.get(strat, 's')
        ax2.plot(strat_df['suite'], strat_df['avg_delay_s'], marker=m, color=c, label=strat, linewidth=2)
    ax2.set_title("Average Delivery Delay (s)", fontsize=12, fontweight='bold')
    ax2.set_ylabel("Delay (seconds)")
    ax2.grid(True, linestyle='--', alpha=0.5)
    ax2.tick_params(axis='x', rotation=35)
    ax2.legend(loc='upper right')

    # 3. Transmissions per Delivered (Overhead)
    ax3 = axes[1, 0]
    for strat in strategies:
        strat_df = df[df['strategy'] == strat]
        c = colors.get(strat, None)
        m = markers.get(strat, '^')
        ax3.plot(strat_df['suite'], strat_df['tx_per_delivered'], marker=m, color=c, label=strat, linewidth=2)
    ax3.set_title("Overhead: Transmissions / Delivered Msg", fontsize=12, fontweight='bold')
    ax3.set_ylabel("Transmissions / Delivered")
    ax3.grid(True, linestyle='--', alpha=0.5)
    ax3.tick_params(axis='x', rotation=35)
    ax3.legend(loc='upper left')

    # 4. Average Hops Traversed
    ax4 = axes[1, 1]
    for strat in strategies:
        strat_df = df[df['strategy'] == strat]
        c = colors.get(strat, None)
        m = markers.get(strat, 'd')
        hops = strat_df['avg_hops'] if 'avg_hops' in strat_df.columns else [0] * len(strat_df)
        ax4.plot(strat_df['suite'], hops, marker=m, color=c, label=strat, linewidth=2)
    ax4.set_title("Average Hop Count", fontsize=12, fontweight='bold')
    ax4.set_ylabel("Hops Traversed")
    ax4.set_ylim(-0.2, 5.5)
    ax4.grid(True, linestyle='--', alpha=0.5)
    ax4.tick_params(axis='x', rotation=35)
    ax4.legend(loc='upper left')

    plt.suptitle("OppMesh Opportunistic Routing Evaluation Benchmark", fontsize=15, fontweight='bold')
    plt.tight_layout()
    output_png = "simulation_results.png"
    plt.savefig(output_png)
    print(f"Saved evaluation plots to {output_png}")

if __name__ == "__main__":
    main()
