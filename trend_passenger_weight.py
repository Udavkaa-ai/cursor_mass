import pandas as pd
import numpy as np
import re
from collections import defaultdict
import matplotlib.pyplot as plt

AGE_FILE = 'Возрастно-половая структура пассажиропотока.xlsx'
GENDER_FILE = 'Гендер.xlsx'
ROSSTAT_FILE = 'ЦФО_ср вес.xlsx'
YEARS = list(range(2019, 2025))  # 2019-2024

# --- 1. Пассажирская структура -------------------------------------------------

def parse_age_counts():
    df = pd.read_excel(AGE_FILE, sheet_name='Возраст', header=None)
    header_row = 1
    years_in_file = [int(col) for col in df.iloc[header_row, [1, 3, 5, 7]].tolist()]
    age_labels = df.iloc[2:8, 0].tolist()  # 6 строк возрастов
    counts = {}
    for i, yr in enumerate(years_in_file):
        col_idx = 1 + 2 * i
        counts_year = df.iloc[2:8, col_idx].astype(int).tolist()
        counts[yr] = dict(zip(age_labels, counts_year))
    return counts, age_labels, years_in_file


def parse_gender_counts():
    df = pd.read_excel(GENDER_FILE, header=None)
    header_row = 1
    years_in_file = [int(col) for col in df.iloc[header_row, [1, 3, 5, 7]].tolist()]
    gender_labels = df.iloc[2:5, 0].tolist()  # male, female, undefined
    counts = {}
    for i, yr in enumerate(years_in_file):
        col_idx = 1 + 2 * i
        counts_year = df.iloc[2:5, col_idx].astype(int).tolist()
        counts[yr] = dict(zip(gender_labels, counts_year))
    return counts, gender_labels, years_in_file

age_counts_dict, age_labels, age_years = parse_age_counts()
gender_counts_dict, gender_labels, gender_years = parse_gender_counts()

# В случае отсутствия 2019-2020 предполагаем ту же структуру, что и 2021 г.
for yr in YEARS:
    if yr not in age_counts_dict:
        age_counts_dict[yr] = age_counts_dict[min(age_years)]  # копия 2021
    if yr not in gender_counts_dict:
        gender_counts_dict[yr] = gender_counts_dict[min(gender_years)]

# вычисляем доли
age_share = {yr: {age: cnt / sum(age_counts_dict[yr].values()) for age, cnt in age_counts_dict[yr].items()} for yr in YEARS}
gender_share = {yr: {g: cnt / sum(gender_counts_dict[yr].values()) for g, cnt in gender_counts_dict[yr].items()} for yr in YEARS}

# --- 2. Росстат: веса по возрасту и полу ---------------------------------------

def extract_rosstat_weights(year):
    """Возвращает dict {(age_int, gender): weight} для заданного года."""
    df = pd.read_excel(ROSSTAT_FILE, sheet_name=str(year))
    # Столбцы: 0 – возрастной интервал, 1 – оба пола, 2 – мужчины, 3 – женщины
    weights = defaultdict(dict)  # weights[age][gender] = value
    for _, row in df.iterrows():
        label = str(row.iloc[0]).strip()
        if re.match(r'\d+-\d+', label):
            a0, a1 = map(int, re.findall(r'\d+', label)[:2])
        elif re.match(r'\d+ .*и более', label):
            a0 = int(re.findall(r'\d+', label)[0])
            a1 = 100
        elif re.match(r'\d+-\d+ лет', label):
            a0, a1 = map(int, re.findall(r'\d+', label)[:2])
        else:
            continue
        for age in range(a0, a1 + 1):
            weights[age]['Оба пола'] = row.iloc[1]
            weights[age]['МУЖЧИНЫ'] = row.iloc[2]
            weights[age]['ЖЕНЩИНЫ'] = row.iloc[3]
    return weights

rosstat_cache = {yr: extract_rosstat_weights(yr) for yr in YEARS}

# --- 3. Средний вес по диапазонам ДОСС -----------------------------------------

doss_ranges = {
    '0-5': (0, 5),
    '6-10': (6, 10),
    '11-20': (11, 20),
    '21-40': (21, 40),
    '41-60': (41, 60),
    'от 60 и выше': (60, 100)
}

# gender labels map: we only consider МУЖЧИНЫ, ЖЕНЩИНЫ. Неопределён присвоим среднее обоих полов.

def avg_weight_range(year, age_label, gender_key):
    a0, a1 = doss_ranges[age_label]
    wts = []
    weights = rosstat_cache[year]
    for age in range(a0, a1 + 1):
        if gender_key == 'NEUTRAL':
            wts.append(weights[age]['Оба пола'])
        else:
            wts.append(weights[age][gender_key])
    return float(np.mean(wts))

# --- 4. Расчёт среднего веса пассажира по годам --------------------------------

avg_weight_year = {}
contributions = []  # list of dicts per year (t vs t-1)

for yr in YEARS:
    # матрица долей по независимому предположению
    wt_sum = 0.0
    for age_label in doss_ranges:
        for gender in ['МУЖЧИНЫ', 'ЖЕНЩИНЫ']:
            share = age_share[yr][age_label] * gender_share[yr][gender]
            w = avg_weight_range(yr, age_label, gender)
            wt_sum += share * w
    # + неопределённый гендер – предположим средний вес обоих
    if 'НЕ\nОПРЕДЕЛЕН' in gender_share[yr]:
        gshare = gender_share[yr]['НЕ\nОПРЕДЕЛЕН']
        # возьмём в качестве веса среднее между муж и жен в каждой возрастной группе
        for age_label in doss_ranges:
            share = age_share[yr][age_label] * gshare
            wm = avg_weight_range(yr, age_label, 'МУЖЧИНЫ')
            wf = avg_weight_range(yr, age_label, 'ЖЕНЩИНЫ')
            wavg = (wm + wf) / 2
            wt_sum += share * wavg
    avg_weight_year[yr] = wt_sum

# --- 5. Декомпозиция изменений --------------------------------------------------

def decompose(t_prev, t_curr):
    """Возвращает (d_total, d_weight, d_structure) для перехода от t_prev к t_curr."""
    d_total = avg_weight_year[t_curr] - avg_weight_year[t_prev]
    # эффект изменения веса населения (при фиксиров. структуре t_prev)
    weight_eff = 0.0
    struct_eff = 0.0
    for age_label in doss_ranges:
        for gender in ['МУЖЧИНЫ', 'ЖЕНЩИНЫ']:
            S_prev = age_share[t_prev][age_label] * gender_share[t_prev][gender]
            W_prev = avg_weight_range(t_prev, age_label, gender)
            W_curr = avg_weight_range(t_curr, age_label, gender)
            weight_eff += S_prev * (W_curr - W_prev)
            S_curr = age_share[t_curr][age_label] * gender_share[t_curr][gender]
            struct_eff += (S_curr - S_prev) * W_curr
        # неопределённый гендер
    return d_total, weight_eff, struct_eff

for i in range(1, len(YEARS)):
    t_prev = YEARS[i-1]
    t_curr = YEARS[i]
    d_total, d_w, d_s = decompose(t_prev, t_curr)
    contributions.append({'year': t_curr, 'ΔСредний вес': d_total, 'ΔВес населения': d_w, 'ΔСтруктура пассажиров': d_s})

# --- 6. Вывод таблиц ------------------------------------------------------------
print('\nСредний вес пассажира по годам:')
for yr in YEARS:
    print(f"{yr}: {avg_weight_year[yr]:.2f} кг")

print('\nДекомпозиция изменений (кг):')
for row in contributions:
    print(row)

# --- 7. Визуализация тренда -----------------------------------------------------
plt.figure(figsize=(10, 6))
plt.plot(YEARS, [avg_weight_year[y] for y in YEARS], marker='o')
plt.title('Тренд среднего веса пассажира (2019-2024)')
plt.xlabel('Год')
plt.ylabel('Средний вес, кг')
plt.grid(True)
plt.savefig('trend_avg_weight.png', dpi=300)

# Сохраняем таблицы в Excel
with pd.ExcelWriter('trend_passenger_weight.xlsx') as writer:
    pd.DataFrame({'Год': YEARS, 'СреднийВес': [avg_weight_year[y] for y in YEARS]}).to_excel(writer, sheet_name='Trend', index=False)
    pd.DataFrame(contributions).to_excel(writer, sheet_name='Contributions', index=False)

print('\nФайлы сохранены: trend_avg_weight.png, trend_passenger_weight.xlsx')