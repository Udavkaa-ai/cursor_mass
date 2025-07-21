import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

POP_FILE = 'population_cfo.xlsx'
AGE_FILE = 'Возрастно-половая структура пассажиропотока.xlsx'
GENDER_FILE = 'Гендер.xlsx'

# 1. Парсим пассажирские доли (как в предыдущем скрипте)

def get_passenger_shares():
    age_df = pd.read_excel(AGE_FILE, sheet_name='Возраст', header=None)
    age_labels = age_df.iloc[2:8,0].tolist()
    years_cols = [1,3,5,7]  # 2021-2024
    total_2021_24 = age_df.iloc[2:8,9].astype(int).tolist()
    age_share = {lbl: cnt / sum(total_2021_24) for lbl, cnt in zip(age_labels,total_2021_24)}

    gender_df = pd.read_excel(GENDER_FILE, header=None)
    gender_labels = ['МУЖЧИНЫ','ЖЕНЩИНЫ']
    total_gender_counts = gender_df.iloc[2:4,9].astype(int).tolist()
    gender_share = {lbl: cnt/sum(total_gender_counts) for lbl,cnt in zip(gender_labels,total_gender_counts)}
    return age_share, gender_share

# 2. Парсим население ЦФО 2024 (последний год в файле)

def parse_population():
    df = pd.read_excel(POP_FILE, sheet_name='Таблица', header=None)
    # locate ЦФО rows
    row_female = df[df.iloc[:,0]=='Центральный федеральный округ'].index[0] + 1
    # ищем первую строку ниже, в которой в колонке с возрастом 0–4 (col 4) стоит число – это будут мужчины
    row_male = row_female + 6  # в таблице блок мужчин находится через 6 строк
    label_row = row_female - 3  # row with age labels (0-4 etc.)

    ranges_counts = {}
    for col in range(df.shape[1]-1):
        label = df.iloc[label_row, col]
        if isinstance(label,str) and '–' in label:
            count_f = df.iloc[row_female, col+1]
            count_m = df.iloc[row_male, col+1]
            if pd.isna(count_f) or pd.isna(count_m):
                continue
            total = float(count_f)+float(count_m)
            ranges_counts[label.strip()] = total
    return ranges_counts

# 3. Агрегируем в интервалы ДОСС (с грубыми коэффициентами для пересечения)

def aggregate_population(pop_counts):
    # pop_counts содержит 5-летки
    def take(label):
        return pop_counts.get(label,0)
    agg = {}
    agg['0-5'] = take('0 – 4') + 0.2*take('5 – 9')
    agg['6-10'] = 0.8*take('5 – 9') + 0.5*take('10 – 14')
    agg['11-20'] = 0.5*take('10 – 14') + take('15 – 19')
    agg['21-40'] = take('20 – 24')+take('25 – 29')+take('30 – 34')+take('35 – 39')+0.0*take('40 – 44')*0
    agg['41-60'] = take('40 – 44')+take('45 – 49')+take('50 – 54')+take('55 – 59')
    agg['от 60 и выше'] = sum(v for k,v in pop_counts.items() if any(x in k for x in ['60','65','70','75','80','85','90','95','100']))
    total = sum(agg.values())
    share = {k:v/total for k,v in agg.items()}
    return share


def main():
    age_share_pass, gender_share_pass = get_passenger_shares()
    pop_counts = parse_population()
    pop_share = aggregate_population(pop_counts)

    # пассажирская возрастная доля (без пола)
    pass_age_only = age_share_pass
    # общенасел.

    comp = pd.DataFrame({
        'Доля_пассажиры': pass_age_only,
        'Доля_население': pop_share
    })
    comp['Индекс_предст']=comp['Доля_пассажиры']/comp['Доля_население']
    print(comp)

    comp.to_excel('population_vs_passengers_CFO.xlsx')
    comp[['Доля_пассажиры','Доля_население']].plot(kind='bar', figsize=(10,6))
    plt.title('Доли возрастных групп: пассажиры vs население ЦФО (2024)')
    plt.ylabel('Доля')
    plt.xticks(rotation=45)
    plt.tight_layout()
    plt.savefig('population_vs_passengers.png', dpi=300)
    print('\nФайлы сохранены: population_vs_passengers_CFO.xlsx, population_vs_passengers.png')

if __name__=='__main__':
    main()