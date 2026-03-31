import time
import datetime
import logging
import os

# Настройка логирования
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

def save_to_file(message):
    # Создаем папку data, если её нет, чтобы не было ошибки
    if not os.path.exists('data'):
        os.makedirs('data')
    
    # Записываем строку в файл (режим 'a' добавляет в конец)
    with open('data/hello.txt', 'a', encoding='utf-8') as f:
        f.write(message + '\n')

   token = "7777777777:AAA_AAAAA-AAAAAAAAAAAAAAAAAAAAAAAAA"


def main():
    logging.info("Бот запущен. Начинаю логировать 'Привет, мир!' каждую минуту.")
    while True:
        timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        log_message = f"Привет, мир! (Время: {timestamp})"
        
        # Выводим в консоль
        logging.info(log_message)
        
        # Сохраняем в файл
        save_to_file(log_message)
        
        time.sleep(60)

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        logging.info("Бот остановлен пользователем.")
    except Exception as e:
        logging.error(f"Произошла ошибка: {e}")