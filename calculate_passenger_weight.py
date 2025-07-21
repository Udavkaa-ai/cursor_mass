import pandas as pd
import matplotlib.pyplot as plt
import re
from collections import defaultdict

# File paths (assumed to be in workspace root)
AGE_FILE = 'Возрастно-половая структура пассажиропотока.xlsx'
ROSSTAT_FILE = 'ЦФО_ср вес.xlsx'
ROSSTAT_YEAR = '2024'  # the most granular year available

# 1. Читаем данные о распределении пассажиров по возрасту (ДОСС)
age_df = pd.read_excel(AGE_FILE, sheet_name='Возраст')
age_df.columns.values[0] = 'AgeRange'
# Столбец с суммарным трафиком за 2021–2024 гг.
total_col = age_df.columns[9]
passenger_age = age_df[['AgeRange', total_col]].copy()
passenger_age.columns = ['AgeRange', 'Passengers']
# Оставляем только нужные возрастные строки
target_rows = ['0-5', '6-10', '11-20', '21-40', '41-60', 'от 60 и выше']
passenger_age = passenger_age[passenger_age['AgeRange'].isin(target_rows)].reset_index(drop=True)
passenger_age['Passengers'] = passenger_age['Passengers'].astype(int)

# 2. Читаем данные Росстата по среднему весу
ros_df = pd.read_excel(ROSSTAT_FILE, sheet_name=ROSSTAT_YEAR)
# Найдём столбец с весами «Оба пола» – он находится во второй колонке (index 1)
ros_df = ros_df.iloc[:, :2]  # Age label + both sexes weight
ros_df.columns = ['RosAge', 'Weight']
# Оставляем строки, где есть возрастный диапазон и числовое значение веса
ros_df = ros_df[pd.to_numeric(ros_df['Weight'], errors='coerce').notnull()].copy()
ros_df['Weight'] = ros_df['Weight'].astype(float)

# 3. Преобразуем возрастные группы Росстата в веса для каждого года возраста
age_to_weight = {}
for idx, row in ros_df.iterrows():
    label = str(row['RosAge']).strip()
    w = row['Weight']
    # варианты label: '0-2 лет', '3-6 лет', '7-14 лет', '15-19 лет', '80 лет и более'
    if 'и более' in label:
        m = re.match(r'(\d+) .*', label)
        if m:
            start = int(m.group(1))
            end = 100  # условно
            for age in range(start, end + 1):
                age_to_weight[age] = w
        continue
    m = re.match(r'(\d+)-(\d+)', label)
    if m:
        start, end = map(int, m.groups())
        for age in range(start, end + 1):
            age_to_weight[age] = w
        continue
    # если только одна цифра – пропускаем

# helper, вычисляет средний вес в указанных пределах (вкл.)
def avg_weight(a0: int, a1: int):
    weights = [age_to_weight[age] for age in range(a0, a1 + 1) if age in age_to_weight]
    if not weights:
        return None
    return sum(weights) / len(weights)

# 4. Карта диапазонов ДОСС -> (start, end)
doss_ranges = {
    '0-5': (0, 5),
    '6-10': (6, 10),
    '11-20': (11, 20),
    '21-40': (21, 40),
    '41-60': (41, 60),
    'от 60 и выше': (60, 100)
}

weights_list = []
for label, (a0, a1) in doss_ranges.items():
    w = avg_weight(a0, a1)
    weights_list.append({'AgeRange': label, 'AvgWeight': w})
weights_df = pd.DataFrame(weights_list)

# 5. Объединяем с пассажиропотоком
result = passenger_age.merge(weights_df, on='AgeRange', how='left')
result['TotalMass'] = result['Passengers'] * result['AvgWeight']
result['Share'] = result['Passengers'] / result['Passengers'].sum()

# 6. Средний вес пассажира
overall_avg = result['TotalMass'].sum() / result['Passengers'].sum()
print('\n--- Итоговая таблица ---')
print(result)
print(f"\nСредний вес пассажира (ВСМ): {overall_avg:.2f} кг")

# 7. Визуализация
plt.figure(figsize=(10, 6))
plt.bar(result['AgeRange'], result['AvgWeight'])
plt.title('Средний вес по возрастным группам (ЦФО, оба пола)')
plt.ylabel('Вес, кг')
plt.xlabel('Возрастной диапазон')
plt.savefig('weight_by_age_range.png', dpi=300)

plt.figure(figsize=(10, 6))
plt.bar(result['AgeRange'], result['Passengers'])
plt.title('Пассажиропоток (2021-2024) по возрастным группам')
plt.ylabel('Пассажиры, чел')
plt.xlabel('Возрастной диапазон')
plt.savefig('passengers_by_age_range.png', dpi=300)

# Круговая диаграмма распределения трафика
autopct = lambda pct: f'{pct:.1f}%'
plt.figure(figsize=(8, 8))
plt.pie(result['Passengers'], labels=result['AgeRange'], autopct=autopct)
plt.title('Доля пассажиров по возрастным группам (2021-2024)')
plt.savefig('passengers_pie.png', dpi=300)

# Экспорт в Excel
with pd.ExcelWriter('average_passenger_weight.xlsx') as writer:
    result.to_excel(writer, sheet_name='Summary', index=False)
    pd.DataFrame({'Metric': ['AverageWeight'], 'Value': [overall_avg]}).to_excel(writer, sheet_name='Result', index=False)

print('\nФайлы с графиками сохранены: weight_by_age_range.png, passengers_by_age_range.png, passengers_pie.png')
print('Таблица сохранена: average_passenger_weight.xlsx')