@Library('utils') _  // Carga la biblioteca 'utils'

pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        NAME_APP = 'config-server-dev'
        SCANNER_HOME = tool 'sonar-scanner'
        CONTAINER_PORT = '8886'
        HOST_PORT = '8886'
        NETWORK = 'azure-net-dev'
        SONAR_TOKEN = credentials('sonar-token')
    }

    stages {
        stage('Compile Repository') {
            steps {
                script {
                    if (params.NOTIFICATION) {
                        notifyJob('JOB Jenkins', params.CORREO)
                    }
                    echo "######################## : ======> EJECUTANDO COMPILE..."
                    // Usar 'bat' para ejecutar comandos en Windows, para Linux usar 'sh'
                    bat 'mvn clean compile'
                }
            }
        }

        stage('QA SonarQube') {
            when {
                expression { params.SONARQUBE } // Ejecutar sólo si el parámetro es verdadero
            }
            steps {
                withSonarQubeEnv('sonar-server') {
                    echo "######################## : ======> EJECUTANDO QA SONARQUBE..."
                    bat """
                        "${SCANNER_HOME}\\bin\\sonar-scanner" ^
                        -Dsonar.url=http://localhost:9000/ ^
                        -Dsonar.login=%SONAR_TOKEN% ^
                        -Dsonar.projectName=config-server ^
                        -Dsonar.java.binaries=. ^
                        -Dsonar.projectKey=config-server
                    """
                }
            }
        }

        stage('OWASP Scan') {
            when {
                expression { params.OWASP } // Ejecutar sólo si el parámetro es verdadero
            }
            steps {
                echo "######################## : ======> EJECUTANDO OWASP SCAN..."
                // dependencyCheck additionalArguments: '--scan ./ --format HTML', odcInstallation: 'DP'
                // dependencyCheck additionalArguments: "--scan ./ --nvdApiKey=${NVD_API_KEY}", odcInstallation: 'DP' // con API KEY
                dependencyCheck additionalArguments: '--scan ./ ', odcInstallation: 'DP' // usaba este
                // dependencyCheck additionalArguments: '--scan ./ --disableCentral --disableRetired --disableExperimental', odcInstallation: 'DP'
                dependencyCheckPublisher pattern: '**/dependency-check-report.xml'
            }
        }

        stage('Build Application with Maven') {
            steps {
                echo "######################## : ======> EJECUTANDO BUILD APPLICATION MAVEN..."
                // Usar 'bat' para ejecutar comandos en Windows, para Linux usar 'sh'
                bat 'mvn clean install'
            }
        }

        stage('Creating Network for Docker') {
            when {
                expression { params.DOCKER }
            }
            steps {
                echo "######################## : ======> EJECUTANDO CREACION DE RED PARA DOCKER..."
                script {
                    def networkExists = bat(
                            script: "docker network ls | findstr ${NETWORK}",
                            returnStatus: true
                    )
                    if (networkExists != 0) {
                        echo "=========> La red '${NETWORK}' no existe. Creándola..."
                        bat "docker network create --attachable ${NETWORK}"
                    } else {
                        echo "=========> La red '${NETWORK}' ya existe. No es necesario crearla."
                    }
                }
            }
        }

        stage('Docker Build and Run') {
            when {
                expression { params.DOCKER }
            }
            steps {
                echo "######################## : ======> EJECUTANDO DOCKER BUILD AND RUN..."
                script {

                    // Obtener la versión en Windows usando un archivo temporal
                    bat '''
                        mvn help:evaluate -Dexpression=project.version -q -DforceStdout > version.txt
                    '''
                    def version = readFile('version.txt').trim()
                    // Remover -SNAPSHOT si existe, solo para PRD, en desarrollo no se quita
                    // version = version.replaceAll("-SNAPSHOT", "")

                    // Verificar si existe el contenedor
                    def containerExists = bat(
                            script: "@docker ps -a --format '{{.Names}}' | findstr /i \"${NAME_APP}\"",
                            returnStatus: true
                    ) == 0

                    // Verificar si existe la imagen
                    def imageExists = bat(
                            script: "@docker images ${NAME_APP}:${version} --format '{{.Repository}}:{{.Tag}}' | findstr /i \"${NAME_APP}:${version}\"",
                            returnStatus: true
                    ) == 0

                    if (containerExists || imageExists) {
                        echo "=========> Se encontraron recursos existentes, procediendo a limpiarlos..."

                        if (containerExists) {
                            echo "=========> Eliminando contenedor existente: ${NAME_APP}"
                            bat "docker stop ${NAME_APP}"
                            bat "docker rm ${NAME_APP}"
                        }

                        if (imageExists) {
                            echo "=========> Eliminando imagen existente: ${NAME_APP}"
                            bat "docker rmi ${NAME_APP}:${version}"
                        }
                    } else {
                        echo "=========> No se encontraron recursos existentes, procediendo con el despliegue..."
                    }

                    echo "=========> VERSION A DESPLEGAR: ${version}"
                    echo "=========> APLICATIVO + VERSION: ${NAME_APP}:${version}"

//                    echo "=========> Eliminando contenedores con nombre: ${NAME_APP}..."
//                    bat """
//                        for /F "tokens=*" %%i in ('docker ps -q --filter "name=${NAME_APP}" || exit 0') do @if not "%%i"=="" docker stop %%i
//                    """
//
//                    bat """
//                        for /F "tokens=*" %%i in ('docker ps -a -q --filter "name=${NAME_APP}" || exit 0') do @if not "%%i"=="" docker rm %%i
//                    """
//
//                    echo "=========> Eliminando imagenes con nombre: ${NAME_APP}..."
//                    bat '''
//                        for /F "tokens=*" %%i in ('docker images -q --filter "reference=%NAME_APP%*"') do @if not "%%i"=="" docker rmi %%i
//                    '''
                    def name = NAME_APP.tokenize('-')[0..-2].join('-')
                    bat """
                        echo "=========> Construyendo nueva imagen con versión ${version}..."
                        docker build --build-arg NAME_APP=${name} --build-arg JAR_VERSION=${version} -t ${NAME_APP}:${version} .
                    """
                    bat """
                        echo "=========> Desplegando el contenedor: ${NAME_APP}..."
                        docker run -d --name ${NAME_APP} -p ${HOST_PORT}:${CONTAINER_PORT} --network=${NETWORK} ^
                        --env SERVER_PORT=${HOST_PORT} ^
                        ${NAME_APP}:${version}
                    """
                }
            }
        }
    }

    post {
        success {
            script {
                if (params.NOTIFICATION) {
                    notifyByMail('SUCCESS', params.CORREO)
                }
            }
        }
        failure {
            script {
                if (params.NOTIFICATION) {
                    notifyByMail('FAIL', params.CORREO)
                }
            }
        }
    }
}
