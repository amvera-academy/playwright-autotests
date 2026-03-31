# bot.py
import time
import datetime
import logging

# Настройка логирования
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

def main():
    logging.info("Бот запущен. Начинаю логировать 'Привет, мир!' каждую минуту.")
    while True:
        timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        logging.info(f"Привет, Тимур! (Время: {timestamp})")
        time.sleep(60) # Пауза на 60 секунд (1 минута)

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        logging.info("Бот остановлен пользователем.")
    except Exception as e:
        logging.error(f"Произошла ошибка: {e}")